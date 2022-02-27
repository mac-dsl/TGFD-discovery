package TgfdDiscovery;

import IncrementalRunner.IncUpdates;
import IncrementalRunner.IncrementalChange;
import Infra.*;
import VF2Runner.VF2SubgraphIsomorphism;
import changeExploration.*;
import graphLoader.DBPediaLoader;
import graphLoader.GraphLoader;
import graphLoader.IMDBLoader;
import org.apache.commons.cli.*;
import org.apache.commons.math3.util.CombinatoricsUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.Graph;
import org.jgrapht.GraphMapping;
import org.jgrapht.alg.isomorphism.VF2AbstractIsomorphismInspector;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TgfdDiscovery {
	public static final int DEFAULT_NUM_OF_SNAPSHOTS = 3;
	public static final String NO_REUSE_MATCHES_PARAMETER_TEXT = "noReuseMatches";
	public static final String CHANGEFILE_PARAMETER_TEXT = "changefile";
	private static final int DEFAULT_MAX_LITERALS_NUM = 0;
	public static final String FREQUENT_SIZE_SET_PARAM = "f";
	public static final String MAX_LIT_PARAM = "maxLit";
	protected static Integer INDIVIDUAL_VERTEX_INDEGREE_FLOOR = 25;
	public static double MEDIAN_SUPER_VERTEX_TYPE_INDEGREE_FLOOR = 25.0;
	public static final double DEFAULT_MAX_SUPER_VERTEX_DEGREE = 1500.0;
	public static final double DEFAULT_AVG_SUPER_VERTEX_DEGREE = 30.0;
	private int maxNumOfLiterals = DEFAULT_MAX_LITERALS_NUM;
	private int t = DEFAULT_NUM_OF_SNAPSHOTS;
	private boolean dissolveSuperVerticesBasedOnCount = false;
	private double superVertexDegree = MEDIAN_SUPER_VERTEX_TYPE_INDEGREE_FLOOR;
	private boolean useTypeChangeFile = false;
	private boolean dissolveSuperVertexTypes = false;
	private boolean validationSearch = false;
	private String path = ".";
	private int numOfSnapshots;
	public static final int DEFAULT_FREQUENT_SIZE_SET = Integer.MAX_VALUE;
	public static final int DEFAULT_GAMMA = 20;
	public static final int DEFAULT_K = 3;
	public static final double DEFAULT_TGFD_THETA = 0.25;
	public static final double DEFAULT_PATTERN_THETA = 0.05;
	private boolean reUseMatches = true;
	private boolean generatek0Tgfds = false;
    private boolean skipK1 = false;
    private Integer numOfEdgesInAllGraphs;
	private int numOfVerticesInAllGraphs;
	public Map<String, HashSet<String>> vertexTypesToAttributesMap; // freq attributes come from here
	public PatternTree patternTree;
	private boolean hasMinimalityPruning = true;
	private String graphSize = null;
	private boolean onlyInterestingTGFDs = true;
	private int k = DEFAULT_K;
	private double tgfdTheta = DEFAULT_TGFD_THETA;
	private double patternTheta = DEFAULT_PATTERN_THETA;
	private int gamma = DEFAULT_GAMMA;
	private int frequentSetSize = DEFAULT_FREQUENT_SIZE_SET;
	private HashSet<String> activeAttributesSet;
	private int previousLevelNodeIndex = 0;
	private int candidateEdgeIndex = 0;
	private int currentVSpawnLevel = 0;
	private ArrayList<ArrayList<TGFD>> tgfds;
	private long startTime;
	private final ArrayList<Long> kRuntimes = new ArrayList<>();
	private String timeAndDateStamp = null;
	private boolean kExperiment = false;
	private boolean useChangeFile = false;
	private List<Entry<String, Integer>> sortedVertexHistogram; // freq nodes come from here
	private List<Entry<String, Integer>> sortedFrequentEdgesHistogram; // freq edges come from here
	private final HashMap<String, Integer> vertexHistogram = new HashMap<>();
	private boolean hasSupportPruning = true;
	private ArrayList<Double> patternSupportsList = new ArrayList<>();
	private ArrayList<Double> constantTgfdSupportsList = new ArrayList<>();
	private ArrayList<Double> generalTgfdSupportsList = new ArrayList<>();
	private final ArrayList<Double> vertexFrequenciesList = new ArrayList<>();
	private final ArrayList<Double> edgeFrequenciesList = new ArrayList<>();
	private long totalVisitedPathCheckingTime = 0;
	private long totalMatchingTime = 0;
	private long totalSupersetPathCheckingTime = 0;
	private long totalFindEntitiesTime = 0;
	private long totalVSpawnTime = 0;
	private long totalDiscoverConstantTGFDsTime = 0;
	private long totalDiscoverGeneralTGFDTime = 0;
	private String experimentName;
	private String loader;
	private List<Entry<String, List<String>>> timestampToFilesMap = new ArrayList<>();
	private HashMap<String, JSONArray> changeFilesMap;
	private List<GraphLoader> graphs;
	private boolean isStoreInMemory = true;
	private Map<String, Double> vertexTypesToAvgInDegreeMap = new HashMap<>();
	private Model firstSnapshotTypeModel = null;
	private Model firstSnapshotDataModel = null;

	public TgfdDiscovery() {
		this.setStartTime(System.currentTimeMillis());

		printInfo();

		this.initializeTgfdLists();
	}

	public TgfdDiscovery(String[] args) {

		String timeAndDateStamp = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu.MM.dd.HH.mm.ss"));
		this.setExperimentDateAndTimeStamp(timeAndDateStamp);
		this.setStartTime(System.currentTimeMillis());

		Options options = TgfdDiscovery.initializeCmdOptions();
		CommandLine cmd = TgfdDiscovery.parseArgs(options, args);

		if (cmd.hasOption("path")) {
			this.path = cmd.getOptionValue("path").replaceFirst("^~", System.getProperty("user.home"));
			if (!Files.isDirectory(Path.of(this.getPath()))) {
				System.out.println(Path.of(this.getPath()) + " is not a valid directory.");
				return;
			}
			this.setGraphSize(Path.of(this.getPath()).getFileName().toString());
		}

		if (!cmd.hasOption("loader")) {
			System.out.println("No specifiedLoader is specified.");
			return;
		} else {
			this.setLoader(cmd.getOptionValue("loader"));
		}

		// TO-DO: this is useless
//		this.setUseChangeFile(this.loader.equalsIgnoreCase("imdb"));

		this.setStoreInMemory(!cmd.hasOption("dontStore"));

		if (cmd.hasOption("name")) {
			this.setExperimentName(cmd.getOptionValue("name"));
		} else  {
			this.setExperimentName("experiment");
		}

		if (!cmd.hasOption("console")) {
			PrintStream logStream = null;
			try {
				logStream = new PrintStream("tgfd-discovery-log-" + timeAndDateStamp + ".txt");
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			System.setOut(logStream);
		}

		this.setMinimalityPruning(!cmd.hasOption("noMinimalityPruning"));
		this.setSupportPruning(!cmd.hasOption("noSupportPruning"));
		this.setOnlyInterestingTGFDs(!cmd.hasOption("uninteresting"));
		this.setUseChangeFile(false);
		boolean validationSearchTemp = false;
		boolean reUseMatchesTemp = !cmd.hasOption(NO_REUSE_MATCHES_PARAMETER_TEXT);
		if (cmd.hasOption("validation")) {
			validationSearchTemp = true;
			reUseMatchesTemp = false;
		} else if (cmd.hasOption(CHANGEFILE_PARAMETER_TEXT)) {
			this.setUseChangeFile(true);
			reUseMatchesTemp = false;
			if (cmd.getOptionValue(CHANGEFILE_PARAMETER_TEXT).equalsIgnoreCase("type")) {
				this.setUseTypeChangeFile(true);
			}
		}
		this.setReUseMatches(reUseMatchesTemp);
		this.setValidationSearch(validationSearchTemp);

		this.setGeneratek0Tgfds(cmd.hasOption("k0"));
		this.setSkipK1(cmd.hasOption("skipK1"));

		this.setT(cmd.getOptionValue("t") == null ? TgfdDiscovery.DEFAULT_NUM_OF_SNAPSHOTS : Integer.parseInt(cmd.getOptionValue("t")));
		this.setGamma(cmd.getOptionValue("a") == null ? TgfdDiscovery.DEFAULT_GAMMA : Integer.parseInt(cmd.getOptionValue("a")));
		this.setMaxNumOfLiterals(cmd.getOptionValue(MAX_LIT_PARAM) == null ? TgfdDiscovery.DEFAULT_MAX_LITERALS_NUM : Integer.parseInt(cmd.getOptionValue(MAX_LIT_PARAM)));
		this.setTgfdTheta(cmd.getOptionValue("theta") == null ? TgfdDiscovery.DEFAULT_TGFD_THETA : Double.parseDouble(cmd.getOptionValue("theta")));
		this.setPatternTheta(cmd.getOptionValue("pTheta") == null ? this.getTgfdTheta() : Double.parseDouble(cmd.getOptionValue("pTheta")));
		this.setK(cmd.getOptionValue("k") == null ? TgfdDiscovery.DEFAULT_K : Integer.parseInt(cmd.getOptionValue("k")));
		this.setFrequentSetSize(cmd.getOptionValue(FREQUENT_SIZE_SET_PARAM) == null ? TgfdDiscovery.DEFAULT_FREQUENT_SIZE_SET : Integer.parseInt(cmd.getOptionValue(FREQUENT_SIZE_SET_PARAM)));

		this.initializeTgfdLists();

		if (cmd.hasOption("K")) this.markAsKexperiment();

		if (cmd.hasOption("simplifySuperVertexTypes")) {
			MEDIAN_SUPER_VERTEX_TYPE_INDEGREE_FLOOR = Double.parseDouble(cmd.getOptionValue("simplifySuperVertexTypes"));
			this.setDissolveSuperVertexTypes(true);
		} else if (cmd.hasOption("simplifySuperVertex")) {
			INDIVIDUAL_VERTEX_INDEGREE_FLOOR = Integer.valueOf(cmd.getOptionValue("simplifySuperVertex"));
			this.setDissolveSuperVerticesBasedOnCount(true);
		}

		switch (this.getLoader().toLowerCase()) {
			case "dbpedia" -> this.setDBpediaTimestampsAndFilePaths(this.getPath());
			case "citation" -> this.setCitationTimestampsAndFilePaths();
			case "imdb" -> this.setImdbTimestampToFilesMapFromPath(this.getPath());
			default -> {
				System.out.println("No valid loader specified.");
				System.exit(1);
			}
		}
		this.loadGraphsAndComputeHistogram(this.getTimestampToFilesMap());

		printInfo();
	}

	protected void printInfo() {
		String[] info = {
				String.join("=", "loader", this.getGraphSize()),
				String.join("=", "|G|", this.getGraphSize()),
				String.join("=", "t", Integer.toString(this.getT())),
				String.join("=", "k", Integer.toString(this.getK())),
				String.join("=", "pTheta", Double.toString(this.getPatternTheta())),
				String.join("=", "theta", Double.toString(this.getTgfdTheta())),
				String.join("=", "gamma", Double.toString(this.getGamma())),
				String.join("=", "frequentSetSize", Double.toString(this.getFrequentSetSize())),
				String.join("=", "interesting", Boolean.toString(this.isOnlyInterestingTGFDs())),
				String.join("=", "literalMax", Integer.toString(this.getMaxNumOfLiterals())),
				String.join("=", "noMinimalityPruning", Boolean.toString(!this.hasMinimalityPruning())),
				String.join("=", "noSupportPruning", Boolean.toString(!this.hasSupportPruning())),
		};

		System.out.println(String.join(", ", info));
	}

	public static Options initializeCmdOptions() {
		Options options = new Options();
		options.addOption("name", true, "output files will be given the specified name");
		options.addOption("console", false, "print to console");
		options.addOption("noMinimalityPruning", false, "run algorithm without minimality pruning");
		options.addOption("noSupportPruning", false, "run algorithm without support pruning");
		options.addOption("uninteresting", false, "run algorithm and also consider uninteresting TGFDs");
		options.addOption("g", true, "run experiment on a specific graph size");
		options.addOption("t", true, "run experiment using t number of snapshots");
		options.addOption("k", true, "run experiment for k iterations");
		options.addOption("a", true, "run experiment for specified active attribute set size");
		options.addOption("theta", true, "run experiment using a specific support threshold");
		options.addOption("pTheta", true, "run experiment using a specific pattern support threshold");
		options.addOption("K", false, "run experiment for k = 1 to 5");
		options.addOption(FREQUENT_SIZE_SET_PARAM, true, "run experiment using frequent set of p vertices and p edges");
		options.addOption(CHANGEFILE_PARAMETER_TEXT, true, "run experiment using changefiles instead of snapshots");
		options.addOption(NO_REUSE_MATCHES_PARAMETER_TEXT, false, "run experiment without reusing matches between levels");
		options.addOption("k0", false, "run experiment and generate tgfds for single-node patterns");
		options.addOption("loader", true, "run experiment using specified loader");
		options.addOption("path", true, "path to dataset");
		options.addOption("skipK1", false, "run experiment and generate tgfds for k > 1");
		options.addOption("validation", false, "run experiment to test effectiveness of using changefiles");
		options.addOption("simplifySuperVertex", true, "run experiment by collapsing super vertices");
		options.addOption("simplifySuperVertexTypes", true, "run experiment by collapsing super vertex types");
		options.addOption("dontStore", false, "run experiment without storing changefiles in memory, read from disk");
		options.addOption(MAX_LIT_PARAM, true, "run experiment that outputs TGFDs with up n literals");
		return options;
	}

	public static CommandLine parseArgs(Options options, String[] args) {
		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = null;
		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			e.printStackTrace();
		}
		assert cmd != null;
		return cmd;
	}

	public void initialize(List<GraphLoader> graphs) {
		vSpawnInit(graphs);
		if (this.isGeneratek0Tgfds()) {
			this.printTgfdsToFile(this.getExperimentName(), this.getTgfds().get(this.getCurrentVSpawnLevel()));
		}
		this.getkRuntimes().add(System.currentTimeMillis() - this.getStartTime());
		this.patternTree.addLevel();
		this.setCurrentVSpawnLevel(this.getCurrentVSpawnLevel() + 1);
	}

	@Override
	public String toString() {
		String[] info = {"G"+this.getGraphSize()
				, "t" + this.getT()
				, "k" + this.getCurrentVSpawnLevel()
				, "pTheta" + this.getPatternTheta()
				, "theta" + this.getTgfdTheta()
				, "gamma" + this.getGamma()
				, MAX_LIT_PARAM + this.getMaxNumOfLiterals()
				, "freqSet" + (this.getFrequentSetSize() == Integer.MAX_VALUE ? "All" : this.getFrequentSetSize())
				, (this.isValidationSearch() ? "-validation" : "")
				, (this.useChangeFile() ? "-changefile"+(this.isUseTypeChangeFile()?"Type":"All") : "")
				, (!this.reUseMatches() ? "-noMatchesReUsed" : "")
				, (!this.isOnlyInterestingTGFDs() ? "-uninteresting" : "")
				, (!this.hasMinimalityPruning() ? "-noMinimalityPruning" : "")
				, (!this.hasSupportPruning() ? "-noSupportPruning" : "")
				, (this.isDissolveSuperVertexTypes() ? "-simplifySuperTypes"+(this.getSuperVertexDegree()) : "")
				, (this.isDissolveSuperVerticesBasedOnCount() ? "-simplifySuperNodes"+(INDIVIDUAL_VERTEX_INDEGREE_FLOOR) : "")
				, (this.getTimeAndDateStamp() == null ? "" : ("-"+ this.getTimeAndDateStamp()))
		};
		return String.join("-", info);
	}

	public void printTgfdsToFile(String experimentName, ArrayList<TGFD> tgfds) {
		tgfds.sort(new Comparator<TGFD>() {
			@Override
			public int compare(TGFD o1, TGFD o2) {
				return o2.getSupport().compareTo(o1.getSupport());
			}
		});
		System.out.println("Printing TGFDs to file for k = " + this.getCurrentVSpawnLevel());
		try {
			PrintStream printStream = new PrintStream(experimentName + "-tgfds" + this + ".txt");
			printStream.println("k = " + this.getCurrentVSpawnLevel());
			printStream.println("# of TGFDs generated = " + tgfds.size());
			for (TGFD tgfd : tgfds) {
				printStream.println(tgfd);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
//		System.gc();
	}

	public void printExperimentRuntimestoFile() {
		try {
			PrintStream printStream = new PrintStream(this.getExperimentName() + "-runtimes-" + this.getTimeAndDateStamp() + ".txt");
			for (int i  = 0; i < this.getkRuntimes().size(); i++) {
				printStream.print("k = " + i);
				printStream.println(", execution time = " + this.getkRuntimes().get(i));
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		final long startTime = System.currentTimeMillis();

		TgfdDiscovery tgfdDiscovery = new TgfdDiscovery(args);

		tgfdDiscovery.initialize(tgfdDiscovery.getGraphs());
		while (tgfdDiscovery.getCurrentVSpawnLevel() <= tgfdDiscovery.getK()) {

			System.out.println("VSpawn level " + tgfdDiscovery.getCurrentVSpawnLevel());
			System.out.println("Previous level node index " + tgfdDiscovery.getPreviousLevelNodeIndex());
			System.out.println("Candidate edge index " + tgfdDiscovery.getCandidateEdgeIndex());

			PatternTreeNode patternTreeNode = null;
			long vSpawnTime = System.currentTimeMillis();
			while (patternTreeNode == null && tgfdDiscovery.getCurrentVSpawnLevel() <= tgfdDiscovery.getK()) {
				patternTreeNode = tgfdDiscovery.vSpawn();
			}
			vSpawnTime = System.currentTimeMillis()-vSpawnTime;
			TgfdDiscovery.printWithTime("vSpawn", vSpawnTime);
			tgfdDiscovery.addToTotalVSpawnTime(vSpawnTime);
			if (tgfdDiscovery.getCurrentVSpawnLevel() > tgfdDiscovery.getK()) break;
			long matchingTime = System.currentTimeMillis();

			assert patternTreeNode != null;
			List<Set<Set<ConstantLiteral>>> matchesPerTimestamps;
			if (tgfdDiscovery.isValidationSearch()) {
				matchesPerTimestamps = tgfdDiscovery.getMatchesForPattern(tgfdDiscovery.getGraphs(), patternTreeNode);
				matchingTime = System.currentTimeMillis() - matchingTime;
				TgfdDiscovery.printWithTime("getMatchesForPattern", (matchingTime));
				tgfdDiscovery.addToTotalMatchingTime(matchingTime);
			}
			else if (tgfdDiscovery.useChangeFile()) {
				matchesPerTimestamps = tgfdDiscovery.getMatchesUsingChangeFiles(patternTreeNode);
				matchingTime = System.currentTimeMillis() - matchingTime;
				TgfdDiscovery.printWithTime("getMatchesUsingChangeFiles", (matchingTime));
				tgfdDiscovery.addToTotalMatchingTime(matchingTime);
			}
			else {
				matchesPerTimestamps = tgfdDiscovery.findMatchesUsingCenterVertices(tgfdDiscovery.getGraphs(), patternTreeNode);
				matchingTime = System.currentTimeMillis() - matchingTime;
				TgfdDiscovery.printWithTime("findMatchesUsingCenterVertices", (matchingTime));
				tgfdDiscovery.addToTotalMatchingTime(matchingTime);
			}

			if (tgfdDiscovery.doesNotSatisfyTheta(patternTreeNode)) {
				System.out.println("Mark as pruned. Real pattern support too low for pattern " + patternTreeNode.getPattern());
				if (tgfdDiscovery.hasSupportPruning()) patternTreeNode.setIsPruned();
				continue;
			}

			if (tgfdDiscovery.isSkipK1() && tgfdDiscovery.getCurrentVSpawnLevel() == 1) continue;

			final long hSpawnStartTime = System.currentTimeMillis();
			ArrayList<TGFD> tgfds = tgfdDiscovery.hSpawn(patternTreeNode, matchesPerTimestamps);
			TgfdDiscovery.printWithTime("hSpawn", (System.currentTimeMillis() - hSpawnStartTime));
			tgfdDiscovery.getTgfds().get(tgfdDiscovery.getCurrentVSpawnLevel()).addAll(tgfds);
		}
		tgfdDiscovery.printTimeStatistics();
		System.out.println("Total execution time: "+(System.currentTimeMillis() - startTime));
	}

	public void printTimeStatistics() {
		System.out.println("----------------Total Time Statistics-----------------");
		printWithTime("Total vSpawn", this.getTotalVSpawnTime());
		printWithTime("Total Matching", this.getTotalMatchingTime());
		printWithTime("Total Visited Path Checking", this.getTotalVisitedPathCheckingTime());
		printWithTime("Total Superset Path Checking Time", this.getTotalSupersetPathCheckingTime());
		printWithTime("Total Find Entities ", this.getTotalFindEntitiesTime());
		printWithTime("Total Discover Constant TGFDs", this.getTotalDiscoverConstantTGFDsTime());
		printWithTime("Total Discover General TGFD", this.getTotalDiscoverGeneralTGFDTime());
	}

	private int findAllMatchesOfEdgeInSnapshotUsingCenterVertices(PatternTreeNode patternTreeNode, Map<String, List<Integer>> entityURIs, GraphLoader currentSnapshot, HashSet<HashSet<ConstantLiteral>> matchesSet, ArrayList<DataVertex> matchesOfCenterVertexInCurrentSnapshot, DataVertex dataVertex, int timestamp) {
		String centerVertexType = patternTreeNode.getPattern().getCenterVertexType();
		Set<String> edgeLabels = patternTreeNode.getGraph().edgeSet().stream().map(RelationshipEdge::getLabel).collect(Collectors.toSet());
		String sourceType = patternTreeNode.getGraph().edgeSet().iterator().next().getSource().getTypes().iterator().next();
		String targetType = patternTreeNode.getGraph().edgeSet().iterator().next().getTarget().getTypes().iterator().next();

		Set<RelationshipEdge> edgeSet;
		if (centerVertexType.equals(sourceType)) {
			edgeSet = currentSnapshot.getGraph().getGraph().outgoingEdgesOf(dataVertex).stream().filter(e -> edgeLabels.contains(e.getLabel()) && e.getTarget().getTypes().contains(targetType)).collect(Collectors.toSet());
		} else {
			edgeSet = currentSnapshot.getGraph().getGraph().incomingEdgesOf(dataVertex).stream().filter(e -> edgeLabels.contains(e.getLabel()) && e.getSource().getTypes().contains(sourceType)).collect(Collectors.toSet());
		}

		ArrayList<HashSet<ConstantLiteral>> matches = new ArrayList<>();
		int numOfMatchesFound = extractMatches(edgeSet, matches, patternTreeNode, entityURIs, timestamp);
		if (numOfMatchesFound > 0) { // equivalent to results.isomorphismExists()
			matchesSet.addAll(matches);
			matchesOfCenterVertexInCurrentSnapshot.add(dataVertex);
		}
		return numOfMatchesFound;
	}

	private void countTotalNumberOfMatchesFound(List<Set<Set<ConstantLiteral>>> matchesPerTimestamps) {
		int numberOfMatchesFound = 0;
		for (Set<Set<ConstantLiteral>> matchesInOneTimestamp : matchesPerTimestamps) {
			numberOfMatchesFound += matchesInOneTimestamp.size();
		}
		System.out.println("Total number of matches found across all snapshots: " + numberOfMatchesFound);
	}

	private ArrayList<ArrayList<DataVertex>> getListOfMatchesOfCenterVerticesOfThisPattern(List<GraphLoader> graphs, PatternTreeNode patternTreeNode) {
		String centerVertexType = patternTreeNode.getPattern().getCenterVertexType();
		PatternTreeNode centerVertexParent = patternTreeNode.getCenterVertexParent();
		System.out.println("Center vertex type: "+centerVertexType);
		ArrayList<ArrayList<DataVertex>> matchesOfCenterVertex;
		if (centerVertexParent != null && centerVertexParent.getListOfCenterVertices() != null) {
			System.out.println("Found center vertex parent: "+centerVertexParent);
			matchesOfCenterVertex = centerVertexParent.getListOfCenterVertices();
		} else {
			// TO-DO: Can we reduce the possibility of this happening?
			System.out.println("Missing center vertex parent...");
			matchesOfCenterVertex = extractListOfCenterVertices(graphs, centerVertexType);
		}
		return matchesOfCenterVertex;
	}

	public List<Set<Set<ConstantLiteral>>> findMatchesUsingCenterVertices(List<GraphLoader> graphs, PatternTreeNode patternTreeNode) {

		Map<String, List<Integer>> entityURIs = new HashMap<>();

		List<Set<Set<ConstantLiteral>>> matchesPerTimestamps = new ArrayList<>();
		for (int timestamp = 0; timestamp < this.getNumOfSnapshots(); timestamp++) {
			matchesPerTimestamps.add(new HashSet<>());
		}

		this.extractMatchesAcrossSnapshots(graphs, patternTreeNode, matchesPerTimestamps, entityURIs);

		this.countTotalNumberOfMatchesFound(matchesPerTimestamps);

//		for (Map.Entry<String, List<Integer>> entry: entityURIs.entrySet()) {
//			System.out.println(entry);
//		}
		this.setPatternSupport(entityURIs, patternTreeNode);

		return matchesPerTimestamps;
	}

	private void extractMatchesAcrossSnapshots(List<GraphLoader> graphs, PatternTreeNode patternTreeNode, List<Set<Set<ConstantLiteral>>> matchesPerTimestamps, Map<String, List<Integer>> entityURIs) {

		ArrayList<ArrayList<DataVertex>> matchesOfCenterVertex = getListOfMatchesOfCenterVerticesOfThisPattern(graphs, patternTreeNode);

		ArrayList<ArrayList<DataVertex>> newMatchesOfCenterVerticesInAllSnapshots = new ArrayList<>();

		int diameter = patternTreeNode.getPattern().getDiameter();

		System.out.println("Searching for patterns of diameter: " + diameter);
		for (int year = 0; year < graphs.size(); year++) {
			GraphLoader currentSnapshot = graphs.get(year);
			HashSet<HashSet<ConstantLiteral>> matchesSet = new HashSet<>();
			ArrayList<DataVertex> centerVertexMatchesInThisTimestamp = matchesOfCenterVertex.get(year);
			System.out.println("Number of center vertex matches in this timestamp from previous levels: "+centerVertexMatchesInThisTimestamp.size());
			newMatchesOfCenterVerticesInAllSnapshots.add(new ArrayList<>());
			ArrayList<DataVertex> newMatchesOfCenterVertexInCurrentSnapshot = newMatchesOfCenterVerticesInAllSnapshots.get(newMatchesOfCenterVerticesInAllSnapshots.size()-1);
			int numOfMatchesInTimestamp = 0; int processedCount = 0;
			for (DataVertex dataVertex: centerVertexMatchesInThisTimestamp) {
				int numOfMatchesFound;
				if (this.getCurrentVSpawnLevel() == 1) {
					numOfMatchesFound = findAllMatchesOfEdgeInSnapshotUsingCenterVertices(patternTreeNode, entityURIs, currentSnapshot, matchesSet, newMatchesOfCenterVertexInCurrentSnapshot, dataVertex, year);
				} else {
					numOfMatchesFound = findAllMatchesOfPatternInThisSnapshotUsingCenterVertices(patternTreeNode, entityURIs, currentSnapshot, matchesSet, newMatchesOfCenterVertexInCurrentSnapshot, dataVertex, year);
				}
				numOfMatchesInTimestamp += numOfMatchesFound;

				processedCount++;
				if (centerVertexMatchesInThisTimestamp.size() >= 100000 && processedCount % 100000 == 0) {
					System.out.println("Processed " + processedCount + "/" + centerVertexMatchesInThisTimestamp.size());
				}
			}
			System.out.println("Number of matches found: " + numOfMatchesInTimestamp);
			System.out.println("Number of matches found that contain active attributes: " + matchesSet.size());
			System.out.println("Number of center vertex matches in this timestamp in this level: " + newMatchesOfCenterVertexInCurrentSnapshot.size());
			matchesPerTimestamps.get(year).addAll(matchesSet);
		}
		if (this.reUseMatches()) {
			patternTreeNode.setListOfCenterVertices(newMatchesOfCenterVerticesInAllSnapshots);
		}
		System.out.println("Number of entity URIs found: "+entityURIs.size());
	}

	private int findAllMatchesOfPatternInThisSnapshotUsingCenterVertices(PatternTreeNode patternTreeNode, Map<String, List<Integer>> entityURIs, GraphLoader currentSnapshot, HashSet<HashSet<ConstantLiteral>> matchesSet, ArrayList<DataVertex> matchesOfCenterVertexInCurrentSnapshot, DataVertex dataVertex, int timestamp) {
		Set<String> edgeLabels = patternTreeNode.getGraph().edgeSet().stream().map(RelationshipEdge::getLabel).collect(Collectors.toSet());
		int diameter = patternTreeNode.getPattern().getRadius();
		Graph<Vertex, RelationshipEdge> subgraph = currentSnapshot.getGraph().getSubGraphWithinDiameter(dataVertex, diameter, edgeLabels, patternTreeNode.getGraph().edgeSet());
		VF2AbstractIsomorphismInspector<Vertex, RelationshipEdge> results = new VF2SubgraphIsomorphism().execute2(subgraph, patternTreeNode.getPattern(), false);

		int numOfMatchesForCenterVertex = 0;
		ArrayList<HashSet<ConstantLiteral>> matches = new ArrayList<>();
		if (results.isomorphismExists()) {
			numOfMatchesForCenterVertex = extractMatches(results.getMappings(), matches, patternTreeNode, entityURIs, timestamp);
			matchesSet.addAll(matches);
			matchesOfCenterVertexInCurrentSnapshot.add(dataVertex);
		}

		return numOfMatchesForCenterVertex;
	}

	protected void printHistogramStatistics() {
		System.out.println("----------------Statistics for Histogram-----------------");
		Collections.sort(this.vertexFrequenciesList);
		Collections.sort(this.edgeFrequenciesList);
		double medianVertexSupport = 0;
		if (this.vertexFrequenciesList.size() > 0) {
			medianVertexSupport = this.vertexFrequenciesList.size() % 2 != 0 ? this.vertexFrequenciesList.get(this.vertexFrequenciesList.size() / 2) : ((this.vertexFrequenciesList.get(this.vertexFrequenciesList.size() / 2) + this.vertexFrequenciesList.get(this.vertexFrequenciesList.size() / 2 - 1)) / 2);
		}
		double medianEdgeSupport = 0;
		if (this.edgeFrequenciesList.size() > 0) {
			medianEdgeSupport = this.edgeFrequenciesList.size() % 2 != 0 ? this.edgeFrequenciesList.get(this.edgeFrequenciesList.size() / 2) : ((this.edgeFrequenciesList.get(this.edgeFrequenciesList.size() / 2) + this.edgeFrequenciesList.get(this.edgeFrequenciesList.size() / 2 - 1)) / 2);
		}
		System.out.println("Median Vertex Support: " + medianVertexSupport);
		System.out.println("Median Edge Support: " + medianEdgeSupport);
	}

	private void printSupportStatistics() {
		System.out.println("----------------Statistics for vSpawn level "+ this.getCurrentVSpawnLevel() +"-----------------");

		Collections.sort(this.patternSupportsList);
		Collections.sort(this.constantTgfdSupportsList);
		Collections.sort(this.generalTgfdSupportsList);

		double patternSupportsList = 0;
		if (this.patternSupportsList.size() > 0) {
			patternSupportsList = this.patternSupportsList.size() % 2 != 0 ? this.patternSupportsList.get(this.patternSupportsList.size() / 2) : ((this.patternSupportsList.get(this.patternSupportsList.size() / 2) + this.patternSupportsList.get(this.patternSupportsList.size() / 2 - 1)) / 2);
		}
		double constantTgfdSupportsList = 0;
		if (this.constantTgfdSupportsList.size() > 0) {
			constantTgfdSupportsList = this.constantTgfdSupportsList.size() % 2 != 0 ? this.constantTgfdSupportsList.get(this.constantTgfdSupportsList.size() / 2) : ((this.constantTgfdSupportsList.get(this.constantTgfdSupportsList.size() / 2) + this.constantTgfdSupportsList.get(this.constantTgfdSupportsList.size() / 2 - 1)) / 2);
		}
		double generalTgfdSupportsList = 0;
		if (this.generalTgfdSupportsList.size() > 0) {
			generalTgfdSupportsList = this.generalTgfdSupportsList.size() % 2 != 0 ? this.generalTgfdSupportsList.get(this.generalTgfdSupportsList.size() / 2) : ((this.generalTgfdSupportsList.get(this.generalTgfdSupportsList.size() / 2) + this.generalTgfdSupportsList.get(this.generalTgfdSupportsList.size() / 2 - 1)) / 2);
		}

		System.out.println("Median Pattern Support: " + patternSupportsList);
		System.out.println("Median Constant TGFD Support: " + constantTgfdSupportsList);
		System.out.println("Median General TGFD Support: " + generalTgfdSupportsList);
		// Reset for each level of vSpawn
		this.patternSupportsList = new ArrayList<>();
		this.constantTgfdSupportsList = new ArrayList<>();
		this.generalTgfdSupportsList = new ArrayList<>();
	}

	public void markAsKexperiment() {
		this.setExperimentName("vary-k");
		this.setkExperiment(true);
	}

	public void setExperimentDateAndTimeStamp(String timeAndDateStamp) {
		this.setTimeAndDateStamp(timeAndDateStamp);
	}

	protected void printVertexAndEdgeStatisticsForEntireTemporalGraph(List<?> graphs, Map<String, Integer> vertexTypesHistogram) {
		System.out.println("Number of vertices across all graphs: " + this.getNumOfVerticesInAllGraphs());
		System.out.println("Number of vertex types across all graphs: " + vertexTypesHistogram.size());
		if (graphs.stream().allMatch(c -> c instanceof GraphLoader)) {
			System.out.println("Number of edges in each snapshot before collapsing super vertices...");
			long totalEdgeCount = 0;
			for (GraphLoader graph : (List<GraphLoader>) graphs) {
				long edgeCount = graph.getGraph().getGraph().edgeSet().size();
				totalEdgeCount += edgeCount;
				System.out.println(edgeCount);
			}
			System.out.println("Number of edges across all graphs: " + totalEdgeCount);
		}
	}

	private void calculateAverageInDegree(Map<String, List<Integer>> vertexTypesToInDegreesMap) {
		System.out.println("Average in-degrees of vertex types...");
		List<Double> avgInDegrees = new ArrayList<>();
		for (Entry<String, List<Integer>> entry: vertexTypesToInDegreesMap.entrySet()) {
			if (entry.getValue().size() == 0) continue;
			entry.getValue().sort(Comparator.naturalOrder());
			double avgInDegree = (double) entry.getValue().stream().mapToInt(Integer::intValue).sum() / (double) entry.getValue().size();
			System.out.println(entry.getKey()+": "+avgInDegree);
			avgInDegrees.add(avgInDegree);
			this.getVertexTypesToAvgInDegreeMap().put(entry.getKey(), avgInDegree);
		}
//		double avgInDegree = avgInDegrees.stream().mapToDouble(Double::doubleValue).sum() / (double) avgInDegrees.size();
		double avgInDegree = this.getHighOutlierThreshold(avgInDegrees);
		this.setSuperVertexDegree(Math.max(avgInDegree, DEFAULT_AVG_SUPER_VERTEX_DEGREE));
		System.out.println("Super vertex degree is "+ this.getSuperVertexDegree());
	}

	protected void calculateMedianInDegree(Map<String, List<Integer>> vertexTypesToInDegreesMap) {
		System.out.println("Median in-degrees of vertex types...");
		List<Double> medianInDegrees = new ArrayList<>();
		for (Entry<String, List<Integer>> entry: vertexTypesToInDegreesMap.entrySet()) {
			if (entry.getValue().size() == 0) {
				this.getVertexTypesToAvgInDegreeMap().put(entry.getKey(), 0.0);
				continue;
			}
			entry.getValue().sort(Comparator.naturalOrder());
			double medianInDegree;
			if (entry.getValue().size() % 2 == 0) {
				medianInDegree = (entry.getValue().get(entry.getValue().size()/2) + entry.getValue().get(entry.getValue().size()/2-1))/2.0;
			} else {
				medianInDegree = entry.getValue().get(entry.getValue().size()/2);
			}
			System.out.println(entry.getKey()+": "+medianInDegree);
			medianInDegrees.add(medianInDegree);
			this.getVertexTypesToAvgInDegreeMap().put(entry.getKey(), medianInDegree);
		}
//		double medianInDegree;
//		if (medianInDegrees.size() % 2 == 0) {
//			medianInDegree = (medianInDegrees.get(medianInDegrees.size()/2) + medianInDegrees.get(medianInDegrees.size()/2-1))/2.0;
//		} else {
//			medianInDegree = medianInDegrees.get(medianInDegrees.size()/2);
//		}
        double medianInDegree = this.getHighOutlierThreshold(medianInDegrees);
		this.setSuperVertexDegree(Math.max(medianInDegree, MEDIAN_SUPER_VERTEX_TYPE_INDEGREE_FLOOR));
		System.out.println("Super vertex degree is "+ this.getSuperVertexDegree());
	}

	private void calculateMaxInDegree(Map<String, List<Integer>> vertexTypesToInDegreesMap) {
		System.out.println("Max in-degrees of vertex types...");
		List<Double> maxInDegrees = new ArrayList<>();
		for (Entry<String, List<Integer>> entry: vertexTypesToInDegreesMap.entrySet()) {
			if (entry.getValue().size() == 0) continue;
			double maxInDegree = Collections.max(entry.getValue()).doubleValue();
			System.out.println(entry.getKey()+": "+maxInDegree);
			maxInDegrees.add(maxInDegree);
			this.getVertexTypesToAvgInDegreeMap().put(entry.getKey(), maxInDegree);
		}
		double maxInDegree = getHighOutlierThreshold(maxInDegrees);
		System.out.println("Based on histogram, high outlier threshold for in-degree is "+maxInDegree);
		this.setSuperVertexDegree(Math.max(maxInDegree, DEFAULT_MAX_SUPER_VERTEX_DEGREE));
		System.out.println("Super vertex degree is "+ this.getSuperVertexDegree());
	}

	private double getHighOutlierThreshold(List<Double> listOfDegrees) {
		listOfDegrees.sort(Comparator.naturalOrder());
		if (listOfDegrees.size() == 1) return listOfDegrees.get(0);
		double q1, q3;
		if (listOfDegrees.size() % 2 == 0) {
			int halfSize = listOfDegrees.size()/2;
			q1 = listOfDegrees.get(halfSize/2);
			q3 = listOfDegrees.get((halfSize+ listOfDegrees.size())/2);
		} else {
			int middleIndex = listOfDegrees.size()/2;
			List<Double> firstHalf = listOfDegrees.subList(0,middleIndex);
			q1 = firstHalf.get(firstHalf.size()/2);
			List<Double> secondHalf = listOfDegrees.subList(middleIndex, listOfDegrees.size());
			q3 = secondHalf.get(secondHalf.size()/2);
		}
		double iqr = q3 - q1;
		return q3 + (9 * iqr);
	}

	protected void dissolveSuperVerticesAndUpdateHistograms(Map<String, Set<String>> tempVertexAttrFreqMap, Map<String, Set<String>> attrDistributionMap, Map<String, List<Integer>> vertexTypesToInDegreesMap, Map<String, Integer> edgeTypesHistogram) {
		int numOfCollapsedSuperVertices = 0;
		this.setNumOfEdgesInAllGraphs(0);
		this.setNumOfVerticesInAllGraphs(0);
		for (Entry<String, List<Integer>> entry: vertexTypesToInDegreesMap.entrySet()) {
			String superVertexType = entry.getKey();
			if (entry.getValue().size() == 0) continue;
			double medianDegree = this.getVertexTypesToAvgInDegreeMap().get(entry.getKey());
			if (medianDegree > this.getSuperVertexDegree()) {
				System.out.println("Collapsing super vertex "+superVertexType+" with...");
				System.out.println("Degree = "+medianDegree+", Vertex Count = "+this.vertexHistogram.get(superVertexType));
				numOfCollapsedSuperVertices++;
				for (GraphLoader graph: this.getGraphs()) {
					int numOfVertices = 0;
					int numOfAttributes = 0;
					int numOfAttributesAdded = 0;
					int numOfEdgesDeleted = 0;
					for (Vertex v: graph.getGraph().getGraph().vertexSet()) {
						numOfVertices++;
						if (v.getTypes().contains(superVertexType)) {
							// Add edge label as an attribute and delete respective edge
							List<RelationshipEdge> edgesToDelete = new ArrayList<>(graph.getGraph().getGraph().incomingEdgesOf(v));
							for (RelationshipEdge e: edgesToDelete) {
								Vertex sourceVertex = e.getSource();
								Map<String, Attribute> sourceVertexAttrMap = sourceVertex.getAllAttributesHashMap();
								String newAttrName = e.getLabel();
								if (sourceVertexAttrMap.containsKey(newAttrName)) {
									newAttrName = e.getLabel() + "value";
									if (!sourceVertexAttrMap.containsKey(newAttrName)) {
										sourceVertex.putAttributeIfAbsent(new Attribute(newAttrName, v.getAttributeValueByName("uri")));
										numOfAttributesAdded++;
									}
								}
								if (graph.getGraph().getGraph().removeEdge(e)) {
									numOfEdgesDeleted++;
								}
								for (String subjectVertexType: sourceVertex.getTypes()) {
									for (String objectVertexType : e.getTarget().getTypes()) {
										String uniqueEdge = subjectVertexType + " " + e.getLabel() + " " + objectVertexType;
										edgeTypesHistogram.put(uniqueEdge, edgeTypesHistogram.get(uniqueEdge)-1);
									}
								}
							}
							// Update all attribute related histograms
							for (String vertexType : v.getTypes()) {
								tempVertexAttrFreqMap.putIfAbsent(vertexType, new HashSet<>());
								for (String attrName: v.getAllAttributesNames()) {
									if (attrName.equals("uri")) continue;
									numOfAttributes++;
									if (tempVertexAttrFreqMap.containsKey(vertexType)) {
										tempVertexAttrFreqMap.get(vertexType).add(attrName);
									}
									if (!attrDistributionMap.containsKey(attrName)) {
										attrDistributionMap.put(attrName, new HashSet<>());
									}
									attrDistributionMap.get(attrName).add(vertexType);
								}
							}
						}
					}
					System.out.println("Updated count of vertices in graph: " + numOfVertices);
					this.setNumOfVerticesInAllGraphs(this.getNumOfVerticesInAllGraphs()+numOfVertices);

					System.out.println("Number of attributes added to graph: " + numOfAttributesAdded);
					System.out.println("Updated count of attributes in graph: " + numOfAttributes);

					System.out.println("Number of edges deleted from graph: " + numOfEdgesDeleted);
					int newEdgeCount = graph.getGraph().getGraph().edgeSet().size();
					System.out.println("Updated count of edges in graph: " + newEdgeCount);
					this.setNumOfEdgesInAllGraphs(this.getNumOfEdgesInAllGraphs()+newEdgeCount);
				}
			}
		}
		System.out.println("Number of super vertices collapsed: "+numOfCollapsedSuperVertices);
	}

	// TO-DO: Can this be merged with the code in histogram?
	protected void dissolveSuperVerticesBasedOnCount(GraphLoader graph) {
		System.out.println("Dissolving super vertices based on count...");
		System.out.println("Initial edge count of first snapshot: "+graph.getGraph().getGraph().edgeSet().size());
		for (Vertex v: graph.getGraph().getGraph().vertexSet()) {
			int inDegree = graph.getGraph().getGraph().incomingEdgesOf(v).size();
			if (inDegree > INDIVIDUAL_VERTEX_INDEGREE_FLOOR) {
				List<RelationshipEdge> edgesToDelete = new ArrayList<>(graph.getGraph().getGraph().incomingEdgesOf(v));
				for (RelationshipEdge e : edgesToDelete) {
					Vertex sourceVertex = e.getSource();
					Map<String, Attribute> sourceVertexAttrMap = sourceVertex.getAllAttributesHashMap();
					String newAttrName = e.getLabel();
					if (sourceVertexAttrMap.containsKey(newAttrName)) {
						newAttrName = e.getLabel() + "value";
						if (!sourceVertexAttrMap.containsKey(newAttrName)) {
							sourceVertex.putAttributeIfAbsent(new Attribute(newAttrName, v.getAttributeValueByName("uri")));
						}
					}
					graph.getGraph().getGraph().removeEdge(e);
				}
			}
		}
		System.out.println("Updated edge count of first snapshot: "+graph.getGraph().getGraph().edgeSet().size());
	}

	protected int readVertexTypesAndAttributeNamesFromGraph(Map<String, Integer> vertexTypesHistogram, Map<String, Set<String>> tempVertexAttrFreqMap, Map<String, Set<String>> attrDistributionMap, Map<String, List<Integer>> vertexTypesToInDegreesMap, GraphLoader graph, Map<String, Integer> edgeTypesHistogram) {
		int initialEdgeCount = graph.getGraph().getGraph().edgeSet().size();
		System.out.println("Initial count of edges in graph: " + initialEdgeCount);

		int numOfAttributesAdded = 0;
		int numOfEdgesDeleted = 0;
		int numOfVerticesInGraph = 0;
		int numOfAttributesInGraph = 0;
		for (Vertex v: graph.getGraph().getGraph().vertexSet()) {
			numOfVerticesInGraph++;
			for (String vertexType : v.getTypes()) {
				vertexTypesHistogram.merge(vertexType, 1, Integer::sum);
				tempVertexAttrFreqMap.putIfAbsent(vertexType, new HashSet<>());

				for (String attrName: v.getAllAttributesNames()) {
					if (attrName.equals("uri")) continue;
					numOfAttributesInGraph++;
					if (tempVertexAttrFreqMap.containsKey(vertexType)) {
						tempVertexAttrFreqMap.get(vertexType).add(attrName);
					}
					if (!attrDistributionMap.containsKey(attrName)) {
						attrDistributionMap.put(attrName, new HashSet<>());
					}
					attrDistributionMap.get(attrName).add(vertexType);
				}
			}

			int inDegree = graph.getGraph().getGraph().incomingEdgesOf(v).size();
			if (this.isDissolveSuperVerticesBasedOnCount() && inDegree > INDIVIDUAL_VERTEX_INDEGREE_FLOOR) {
				List<RelationshipEdge> edgesToDelete = new ArrayList<>(graph.getGraph().getGraph().incomingEdgesOf(v));
				for (RelationshipEdge e: edgesToDelete) {
					Vertex sourceVertex = e.getSource();
					Map<String, Attribute> sourceVertexAttrMap = sourceVertex.getAllAttributesHashMap();
					String newAttrName = e.getLabel();
					if (sourceVertexAttrMap.containsKey(newAttrName)) {
						newAttrName = e.getLabel() + "value";
						if (!sourceVertexAttrMap.containsKey(newAttrName)) {
							sourceVertex.putAttributeIfAbsent(new Attribute(newAttrName, v.getAttributeValueByName("uri")));
							numOfAttributesAdded++;
						}
					}
					if (graph.getGraph().getGraph().removeEdge(e)) {
						numOfEdgesDeleted++;
					}
					for (String subjectVertexType: sourceVertex.getTypes()) {
						for (String objectVertexType : e.getTarget().getTypes()) {
							String uniqueEdge = subjectVertexType + " " + e.getLabel() + " " + objectVertexType;
							edgeTypesHistogram.merge(uniqueEdge, 1, Integer::sum);
						}
					}
				}
				// Update all attribute related histograms
				for (String vertexType : v.getTypes()) {
					tempVertexAttrFreqMap.putIfAbsent(vertexType, new HashSet<>());
					for (String attrName: v.getAllAttributesNames()) {
						if (attrName.equals("uri")) continue;
						if (tempVertexAttrFreqMap.containsKey(vertexType)) {
							tempVertexAttrFreqMap.get(vertexType).add(attrName);
						}
						if (!attrDistributionMap.containsKey(attrName)) {
							attrDistributionMap.put(attrName, new HashSet<>());
						}
						attrDistributionMap.get(attrName).add(vertexType);
					}
				}
			}
			for (String vertexType : v.getTypes()) {
				if (!vertexTypesToInDegreesMap.containsKey(vertexType)) {
					vertexTypesToInDegreesMap.put(vertexType, new ArrayList<>());
				}
				if (inDegree > 0) {
					vertexTypesToInDegreesMap.get(vertexType).add(inDegree);
				}
			}
		}
		for (Map.Entry<String, List<Integer>> entry: vertexTypesToInDegreesMap.entrySet()) {
			entry.getValue().sort(Comparator.naturalOrder());
		}
		System.out.println("Number of vertices in graph: " + numOfVerticesInGraph);
		System.out.println("Number of attributes in graph: " + numOfAttributesInGraph);

		System.out.println("Number of attributes added to graph: " + numOfAttributesAdded);
		System.out.println("Updated count of attributes in graph: " + (numOfAttributesInGraph+numOfAttributesAdded));

		System.out.println("Number of edges deleted from graph: " + numOfEdgesDeleted);
		int newEdgeCount = graph.getGraph().getGraph().edgeSet().size();
		System.out.println("Updated count of edges in graph: " + newEdgeCount);

		return numOfVerticesInGraph;
	}

	private void calculateSuperVertexDegreeThreshold(Map<String, List<Integer>> vertexTypesToInDegreesMap) {
		List<Long> listOfAverageDegreesAbove1 = new ArrayList<>();
		System.out.println("Average in-degree of each vertex type...");
		for (Entry<String, List<Integer>> entry: vertexTypesToInDegreesMap.entrySet()) {
			long averageDegree = Math.round(entry.getValue().stream().reduce(0, Integer::sum).doubleValue() / (double) entry.getValue().size());
			System.out.println(entry.getKey()+":"+averageDegree);
			listOfAverageDegreesAbove1.add(averageDegree);
		}
		this.setSuperVertexDegree(Math.max(getSuperVertexDegree(), Math.round(listOfAverageDegreesAbove1.stream().reduce(0L, Long::sum).doubleValue() / (double) listOfAverageDegreesAbove1.size())));
	}

	protected void setVertexTypesToAttributesMap(Map<String, Set<String>> tempVertexAttrFreqMap) {
		Map<String, HashSet<String>> vertexTypesToAttributesMap = new HashMap<>();
		for (String vertexType : tempVertexAttrFreqMap.keySet()) {
			Set<String> attrNameSet = tempVertexAttrFreqMap.get(vertexType);
			vertexTypesToAttributesMap.put(vertexType, new HashSet<>());
			for (String attrName : attrNameSet) {
				if (this.activeAttributesSet.contains(attrName)) { // Filters non-active attributes
					vertexTypesToAttributesMap.get(vertexType).add(attrName);
				}
			}
		}

		this.vertexTypesToAttributesMap = vertexTypesToAttributesMap;
	}

	protected void setActiveAttributeSet(Map<String, Set<String>> attrDistributionMap) {
		ArrayList<Entry<String,Set<String>>> sortedAttrDistributionMap = new ArrayList<>(attrDistributionMap.entrySet());
		sortedAttrDistributionMap.sort((o1, o2) -> o2.getValue().size() - o1.getValue().size());
		HashSet<String> mostDistributedAttributesSet = new HashSet<>();
		for (Entry<String, Set<String>> attrNameEntry : sortedAttrDistributionMap.subList(0, Math.min(this.getGamma(), sortedAttrDistributionMap.size()))) {
			mostDistributedAttributesSet.add(attrNameEntry.getKey());
		}
		this.activeAttributesSet = mostDistributedAttributesSet;
	}

	protected int readEdgesInfoFromGraph(Map<String, Integer> edgeTypesHistogram, GraphLoader graph) {
		int numOfEdges = 0;
		for (RelationshipEdge e: graph.getGraph().getGraph().edgeSet()) {
			numOfEdges++;
			Vertex sourceVertex = e.getSource();
			String predicateName = e.getLabel();
			Vertex objectVertex = e.getTarget();
			for (String subjectVertexType: sourceVertex.getTypes()) {
				for (String objectVertexType: objectVertex.getTypes()) {
					String uniqueEdge = subjectVertexType + " " + predicateName + " " + objectVertexType;
					edgeTypesHistogram.merge(uniqueEdge, 1, Integer::sum);
				}
			}
		}
		System.out.println("Number of edges in graph: " + numOfEdges);
		return numOfEdges;
	}

	public void setSortedFrequentVertexTypesHistogram(Map<String, Integer> vertexTypesHistogram) {
		ArrayList<Entry<String, Integer>> sortedVertexTypesHistogram = new ArrayList<>(vertexTypesHistogram.entrySet());
		sortedVertexTypesHistogram.sort((o1, o2) -> o2.getValue() - o1.getValue());
		for (Entry<String, Integer> entry : sortedVertexTypesHistogram) {
			this.vertexHistogram.put(entry.getKey(), entry.getValue());
			double vertexFrequency = (double) entry.getValue() / this.getNumOfVerticesInAllGraphs();
			this.vertexFrequenciesList.add(vertexFrequency);
		}
		this.sortedVertexHistogram = sortedVertexTypesHistogram;
	}

	public void setSortedFrequentEdgeHistogram(Map<String, Integer> edgeTypesHist, Map<String, Integer> vertexTypesHistogram) {
		ArrayList<Entry<String, Integer>> finalEdgesHist = new ArrayList<>();
		for (Entry<String, Integer> entry : edgeTypesHist.entrySet()) {
			String[] edgeString = entry.getKey().split(" ");
			String sourceType = edgeString[0];
			String targetType = edgeString[2];
			this.vertexHistogram.put(sourceType, vertexTypesHistogram.get(sourceType));
			this.vertexHistogram.put(targetType, vertexTypesHistogram.get(targetType));
			double edgeFrequency = (double) entry.getValue() / (double) this.getNumOfEdgesInAllGraphs();
			this.edgeFrequenciesList.add(edgeFrequency);
			if (this.vertexTypesToAttributesMap.get(sourceType).size() > 0 && this.vertexTypesToAttributesMap.get(targetType).size() > 0) {
				finalEdgesHist.add(entry);
			}
		}
		finalEdgesHist.sort((o1, o2) -> o2.getValue() - o1.getValue());
		this.sortedFrequentEdgesHistogram = finalEdgesHist.subList(0, Math.min(finalEdgesHist.size(), this.getFrequentSetSize()));

		Set<String> relevantFrequentVertexTypes = new HashSet<>();
		for (Entry<String, Integer> entry : sortedFrequentEdgesHistogram) {
			String[] edgeString = entry.getKey().split(" ");
			String sourceType = edgeString[0];
			relevantFrequentVertexTypes.add(sourceType);
			String targetType = edgeString[2];
			relevantFrequentVertexTypes.add(targetType);
		}
		Map<String, Integer> relevantVertexTypesHistogram = new HashMap<>();
		for (String relevantVertexType: relevantFrequentVertexTypes) {
			if (vertexTypesHistogram.containsKey(relevantVertexType)) {
				relevantVertexTypesHistogram.put(relevantVertexType, vertexTypesHistogram.get(relevantVertexType));
			}
		}
		this.setSortedFrequentVertexTypesHistogram(relevantVertexTypesHistogram);
	}

	public void loadGraphsAndComputeHistogram(List<Entry<String, List<String>>> timestampToPathsMap) {
		System.out.println("Computing Histogram...");

		final long histogramTime = System.currentTimeMillis();

		Map<String, Integer> vertexTypesHistogram = new HashMap<>();
		Map<String, Set<String>> tempVertexAttrFreqMap = new HashMap<>();
		Map<String, Set<String>> attrDistributionMap = new HashMap<>();
		Map<String, List<Integer>> vertexTypesToInDegreesMap = new HashMap<>();
		Map<String, Integer> edgeTypesHistogram = new HashMap<>();

		this.setGraphs(new ArrayList<>());

		int numOfVerticesAcrossAllGraphs = 0;
		int numOfEdgesAcrossAllGraphs = 0;
		for (Map.Entry<String, List<String>> timestampToPathEntry: timestampToPathsMap) {
			final long graphLoadTime = System.currentTimeMillis();
			GraphLoader graphLoader;
			Model model = ModelFactory.createDefaultModel();
			for (String path : timestampToPathEntry.getValue()) {
				if (!path.toLowerCase().endsWith(".ttl") && !path.toLowerCase().endsWith(".nt"))
					continue;
				if (path.toLowerCase().contains("literals") || path.toLowerCase().contains("objects"))
					continue;
				Path input = Paths.get(path);
				model.read(input.toUri().toString());
			}
			Model dataModel = ModelFactory.createDefaultModel();
			for (String path : timestampToPathEntry.getValue()) {
				if (!path.toLowerCase().endsWith(".ttl") && !path.toLowerCase().endsWith(".nt"))
					continue;
				if (path.toLowerCase().contains("types"))
					continue;
				Path input = Paths.get(path);
				System.out.println("Reading data graph: " + path);
				dataModel.read(input.toUri().toString());
			}
			if (this.getLoader().equals("dbpedia")) {
				graphLoader = new DBPediaLoader(new ArrayList<>(), Collections.singletonList(model), Collections.singletonList(dataModel));
			} else {
				graphLoader = new IMDBLoader(new ArrayList<>(), Collections.singletonList(dataModel));
			}
			if (this.isStoreInMemory() || this.getLoader().equalsIgnoreCase("dbpedia")) {
				if (this.useChangeFile()) {
					if (this.getGraphs().size() == 0) {
						this.getGraphs().add(graphLoader);
						this.loadChangeFilesIntoMemory();
					}
				} else {
					this.getGraphs().add(graphLoader);
				}
			}

			printWithTime("Single graph load", (System.currentTimeMillis() - graphLoadTime));
			final long graphReadTime = System.currentTimeMillis();
			numOfVerticesAcrossAllGraphs += readVertexTypesAndAttributeNamesFromGraph(vertexTypesHistogram, tempVertexAttrFreqMap, attrDistributionMap, vertexTypesToInDegreesMap, graphLoader, edgeTypesHistogram);
			numOfEdgesAcrossAllGraphs += readEdgesInfoFromGraph(edgeTypesHistogram, graphLoader);
			printWithTime("Single graph read", (System.currentTimeMillis() - graphReadTime));
		}

		this.setNumOfVerticesInAllGraphs(numOfVerticesAcrossAllGraphs);
		this.setNumOfEdgesInAllGraphs(numOfEdgesAcrossAllGraphs);

		this.printVertexAndEdgeStatisticsForEntireTemporalGraph(timestampToPathsMap, vertexTypesHistogram);

		final long superVertexHandlingTime = System.currentTimeMillis();
		// TO-DO: What is the best way to estimate a good value for SUPER_VERTEX_DEGREE for each run?
//		this.calculateAverageInDegree(vertexTypesToInDegreesMap);
		this.calculateMedianInDegree(vertexTypesToInDegreesMap);
//		this.calculateMaxInDegree(vertexTypesToInDegreesMap);
		if (this.isDissolveSuperVertexTypes()) {
			if (this.getGraphs().size() > 0) {
				System.out.println("Collapsing vertices with an in-degree above " + this.getSuperVertexDegree());
				this.dissolveSuperVerticesAndUpdateHistograms(tempVertexAttrFreqMap, attrDistributionMap, vertexTypesToInDegreesMap, edgeTypesHistogram);
				printWithTime("Super vertex dissolution", (System.currentTimeMillis() - superVertexHandlingTime));
			}
		}

		this.setActiveAttributeSet(attrDistributionMap);

		this.setVertexTypesToAttributesMap(tempVertexAttrFreqMap);

		System.out.println("Number of edges across all graphs: " + this.getNumOfEdgesInAllGraphs());
		System.out.println("Number of edges labels across all graphs: " + edgeTypesHistogram.size());
		this.setSortedFrequentEdgeHistogram(edgeTypesHistogram, vertexTypesHistogram);

		this.findAndSetNumOfSnapshots();

		printWithTime("All snapshots histogram", (System.currentTimeMillis() - histogramTime));
		printHistogram();
		printHistogramStatistics();
	}

	public void printHistogram() {

		System.out.println("Number of vertex types: " + this.getSortedVertexHistogram().size());
		System.out.println("Frequent Vertices:");
		for (Entry<String, Integer> entry : this.getSortedVertexHistogram()) {
			String vertexType = entry.getKey();
			Set<String> attributes = this.vertexTypesToAttributesMap.get(vertexType);
			System.out.println(vertexType + "={count=" + entry.getValue() + ", support=" + (1.0 * entry.getValue() / this.getNumOfVerticesInAllGraphs()) + ", attributes=" + attributes + "}");
		}

		System.out.println();
		System.out.println("Size of active attributes set: " + this.activeAttributesSet.size());
		System.out.println("Attributes:");
		for (String attrName : this.activeAttributesSet) {
			System.out.println(attrName);
		}
		System.out.println();
		System.out.println("Number of edge types: " + this.sortedFrequentEdgesHistogram.size());
		System.out.println("Frequent Edges:");
		for (Entry<String, Integer> entry : this.sortedFrequentEdgesHistogram) {
			System.out.println("edge=\"" + entry.getKey() + "\", count=" + entry.getValue() + ", support=" +(1.0 * entry.getValue() / this.getNumOfEdgesInAllGraphs()));
		}
		System.out.println();
	}

	public static Map<Set<ConstantLiteral>, ArrayList<Entry<ConstantLiteral, List<Integer>>>> findEntities(AttributeDependency attributes, List<Set<Set<ConstantLiteral>>> matchesPerTimestamps) {
		String yVertexType = attributes.getRhs().getVertexType();
		String yAttrName = attributes.getRhs().getAttrName();
		Set<ConstantLiteral> xAttributes = attributes.getLhs();
		Map<Set<ConstantLiteral>, Map<ConstantLiteral, List<Integer>>> entitiesWithRHSvalues = new HashMap<>();
		int t = 2015;
		for (Set<Set<ConstantLiteral>> matchesInOneTimeStamp : matchesPerTimestamps) {
			System.out.println("---------- Attribute values in " + t + " ---------- ");
			int numOfMatches = 0;
			if (matchesInOneTimeStamp.size() > 0) {
				for(Set<ConstantLiteral> match : matchesInOneTimeStamp) {
					if (match.size() < attributes.size()) continue;
					Set<ConstantLiteral> entity = new HashSet<>();
					ConstantLiteral rhs = null;
					for (ConstantLiteral literalInMatch : match) {
						if (literalInMatch.getVertexType().equals(yVertexType) && literalInMatch.getAttrName().equals(yAttrName)) {
							rhs = new ConstantLiteral(literalInMatch.getVertexType(), literalInMatch.getAttrName(), literalInMatch.getAttrValue());
							continue;
						}
						for (ConstantLiteral attribute : xAttributes) {
							if (literalInMatch.getVertexType().equals(attribute.getVertexType()) && literalInMatch.getAttrName().equals(attribute.getAttrName())) {
								entity.add(new ConstantLiteral(literalInMatch.getVertexType(), literalInMatch.getAttrName(), literalInMatch.getAttrValue()));
							}
						}
					}
					if (entity.size() < xAttributes.size() || rhs == null) continue;

					if (!entitiesWithRHSvalues.containsKey(entity)) {
						entitiesWithRHSvalues.put(entity, new HashMap<>());
					}
					if (!entitiesWithRHSvalues.get(entity).containsKey(rhs)) {
						entitiesWithRHSvalues.get(entity).put(rhs, new ArrayList<>());
					}
					entitiesWithRHSvalues.get(entity).get(rhs).add(t);
					numOfMatches++;
				}
			}
			System.out.println("Number of matches: " + numOfMatches);
			t++;
		}
		if (entitiesWithRHSvalues.size() == 0) return null;

		Comparator<Entry<ConstantLiteral, List<Integer>>> comparator = new Comparator<Entry<ConstantLiteral, List<Integer>>>() {
			@Override
			public int compare(Entry<ConstantLiteral, List<Integer>> o1, Entry<ConstantLiteral, List<Integer>> o2) {
				return o2.getValue().size() - o1.getValue().size();
			}
		};

		Map<Set<ConstantLiteral>, ArrayList<Map.Entry<ConstantLiteral, List<Integer>>>> entitiesWithSortedRHSvalues = new HashMap<>();
		for (Set<ConstantLiteral> entity : entitiesWithRHSvalues.keySet()) {
			Map<ConstantLiteral, List<Integer>> rhsMapOfEntity = entitiesWithRHSvalues.get(entity);
			ArrayList<Map.Entry<ConstantLiteral, List<Integer>>> sortedRhsMapOfEntity = new ArrayList<>(rhsMapOfEntity.entrySet());
			sortedRhsMapOfEntity.sort(comparator);
			entitiesWithSortedRHSvalues.put(entity, sortedRhsMapOfEntity);
		}

		return entitiesWithSortedRHSvalues;
	}

	public HashSet<ConstantLiteral> getActiveAttributesInPattern(Set<Vertex> vertexSet, boolean considerURI) {
		HashMap<String, HashSet<String>> patternVerticesAttributes = new HashMap<>();
		for (Vertex vertex : vertexSet) {
			for (String vertexType : vertex.getTypes()) {
				patternVerticesAttributes.put(vertexType, new HashSet<>());
				Set<String> attrNameSet = this.vertexTypesToAttributesMap.get(vertexType);
				for (String attrName : attrNameSet) {
					patternVerticesAttributes.get(vertexType).add(attrName);
				}
			}
		}
		HashSet<ConstantLiteral> literals = new HashSet<>();
		for (String vertexType : patternVerticesAttributes.keySet()) {
			if (considerURI) literals.add(new ConstantLiteral(vertexType,"uri",null));
			for (String attrName : patternVerticesAttributes.get(vertexType)) {
				ConstantLiteral literal = new ConstantLiteral(vertexType, attrName, null);
				literals.add(literal);
			}
		}
		return literals;
	}

	public boolean isPathVisited(AttributeDependency path, HashSet<AttributeDependency> visitedPaths) {
		long visitedPathCheckingTime = System.currentTimeMillis();
		boolean pathAlreadyVisited = visitedPaths.contains(path);
		visitedPathCheckingTime = System.currentTimeMillis() - visitedPathCheckingTime;
		printWithTime("visitedPathChecking", visitedPathCheckingTime);
		setTotalVisitedPathCheckingTime(getTotalVisitedPathCheckingTime() + visitedPathCheckingTime);
		return pathAlreadyVisited;
	}

	public ArrayList<TGFD> deltaDiscovery(PatternTreeNode patternNode, LiteralTreeNode literalTreeNode, AttributeDependency literalPath, List<Set<Set<ConstantLiteral>>> matchesPerTimestamps) {
		ArrayList<TGFD> tgfds = new ArrayList<>();

		// Add dependency attributes to pattern
		// TO-DO: Fix - when multiple vertices in a pattern have the same type, attribute values get overwritten
		VF2PatternGraph patternForDependency = patternNode.getPattern().copy();
		Set<ConstantLiteral> attributesSetForDependency = new HashSet<>(literalPath.getLhs());
		attributesSetForDependency.add(literalPath.getRhs());
		for (Vertex v : patternForDependency.getPattern().vertexSet()) {
			String vType = new ArrayList<>(v.getTypes()).get(0);
			for (ConstantLiteral attribute : attributesSetForDependency) {
				if (vType.equals(attribute.getVertexType())) {
					v.putAttributeIfAbsent(new Attribute(attribute.getAttrName()));
				}
			}
		}

		System.out.println("Pattern: " + patternForDependency);
		System.out.println("Dependency: " + "\n\tY=" + literalPath.getRhs() + ",\n\tX={" + literalPath.getLhs() + "\n\t}");

		System.out.println("Performing Entity Discovery");

		// Discover entities
		long findEntitiesTime = System.currentTimeMillis();
		Map<Set<ConstantLiteral>, ArrayList<Entry<ConstantLiteral, List<Integer>>>> entities = findEntities(literalPath, matchesPerTimestamps);
		findEntitiesTime = System.currentTimeMillis() - findEntitiesTime;
		printWithTime("findEntitiesTime", findEntitiesTime);
		setTotalFindEntitiesTime(getTotalFindEntitiesTime() + findEntitiesTime);
		if (entities == null) {
			System.out.println("No entities found during entity discovery.");
			if (this.hasSupportPruning()) {
				literalTreeNode.setIsPruned();
				System.out.println("Marked as pruned. Literal path "+literalTreeNode.getPathToRoot());
				patternNode.addZeroEntityDependency(literalPath);
			}
			return tgfds;
		}
		System.out.println("Number of entities discovered: " + entities.size());

		System.out.println("Discovering constant TGFDs");

		// Find Constant TGFDs
		Map<Pair,ArrayList<TreeSet<Pair>>> deltaToPairsMap = new HashMap<>();
		long discoverConstantTGFDsTime = System.currentTimeMillis();
		ArrayList<TGFD> constantTGFDs = discoverConstantTGFDs(patternNode, literalPath.getRhs(), entities, deltaToPairsMap);
		discoverConstantTGFDsTime = System.currentTimeMillis() - discoverConstantTGFDsTime;
		printWithTime("discoverConstantTGFDsTime", discoverConstantTGFDsTime);
		setTotalDiscoverConstantTGFDsTime(getTotalDiscoverConstantTGFDsTime() + discoverConstantTGFDsTime);
		// TO-DO: Try discover general TGFD even if no constant TGFD candidate met support threshold
		System.out.println("Constant TGFDs discovered: " + constantTGFDs.size());
		tgfds.addAll(constantTGFDs);

		System.out.println("Discovering general TGFDs");

		// Find general TGFDs
		if (!deltaToPairsMap.isEmpty()) {
			long discoverGeneralTGFDTime = System.currentTimeMillis();
			ArrayList<TGFD> generalTGFDs = discoverGeneralTGFD(patternNode, patternNode.getPatternSupport(), literalPath, entities.size(), deltaToPairsMap, literalTreeNode);
			discoverGeneralTGFDTime = System.currentTimeMillis() - discoverGeneralTGFDTime;
			printWithTime("discoverGeneralTGFDTime", discoverGeneralTGFDTime);
			setTotalDiscoverGeneralTGFDTime(getTotalDiscoverGeneralTGFDTime() + discoverGeneralTGFDTime);
			if (generalTGFDs.size() > 0) {
				System.out.println("Discovered " + generalTGFDs.size() + " general TGFDs for this dependency.");
				if (this.hasMinimalityPruning()) {
					literalTreeNode.setIsPruned();
					System.out.println("Marked as pruned. Literal path " + literalTreeNode.getPathToRoot());
					patternNode.addMinimalDependency(literalPath);
				}
			}
			tgfds.addAll(generalTGFDs);
		}

		return tgfds;
	}

	private ArrayList<TGFD> discoverGeneralTGFD(PatternTreeNode patternTreeNode, double patternSupport, AttributeDependency literalPath, int entitiesSize, Map<Pair, ArrayList<TreeSet<Pair>>> deltaToPairsMap, LiteralTreeNode literalTreeNode) {

		ArrayList<TGFD> tgfds = new ArrayList<>();

		System.out.println("Number of delta: " + deltaToPairsMap.keySet().size());
		for (Pair deltaPair : deltaToPairsMap.keySet()) {
			System.out.println("constant delta: " + deltaPair);
		}

		System.out.println("Delta to Pairs map...");
		int numOfEntitiesWithDeltas = 0;
		int numOfPairs = 0;
		for (Entry<Pair, ArrayList<TreeSet<Pair>>> deltaToPairsEntry : deltaToPairsMap.entrySet()) {
			numOfEntitiesWithDeltas += deltaToPairsEntry.getValue().size();
			for (TreeSet<Pair> pairSet : deltaToPairsEntry.getValue()) {
				System.out.println(deltaToPairsEntry.getKey()+":"+pairSet);
				numOfPairs += pairSet.size();
			}
		}
		System.out.println("Number of entities with deltas: " + numOfEntitiesWithDeltas);
		System.out.println("Number of pairs: " + numOfPairs);


		// Find intersection delta
		HashMap<Pair, ArrayList<Pair>> intersections = new HashMap<>();
		int currMin = 0;
		int currMax = this.getNumOfSnapshots() - 1;
		// TO-DO: Verify if TreeSet<Pair> is being sorted correctly
		// TO-DO: Does this method only produce intervals (x,y), where x == y ?
		ArrayList<Pair> currSatisfyingAttrValues = new ArrayList<>();
		for (Pair deltaPair: deltaToPairsMap.keySet()) {
			if (Math.max(currMin, deltaPair.min()) <= Math.min(currMax, deltaPair.max())) {
				currMin = Math.max(currMin, deltaPair.min());
				currMax = Math.min(currMax, deltaPair.max());
//				currSatisfyingAttrValues.add(satisfyingPairsSet.get(index)); // By axiom 4
				continue;
			}
			for (Entry<Pair, ArrayList<TreeSet<Pair>>> deltaToPairsEntry : deltaToPairsMap.entrySet()) {
				for (TreeSet<Pair> satisfyingPairSet : deltaToPairsEntry.getValue()) {
					for (Pair satisfyingPair : satisfyingPairSet) {
						if (satisfyingPair.max() - satisfyingPair.min() >= currMin && satisfyingPair.max() - satisfyingPair.min() <= currMax) {
							currSatisfyingAttrValues.add(new Pair(satisfyingPair.min(), satisfyingPair.max()));
						}
					}
				}
			}
			intersections.putIfAbsent(new Pair(currMin, currMax), currSatisfyingAttrValues);
			currSatisfyingAttrValues = new ArrayList<>();
			currMin = 0;
			currMax = this.getNumOfSnapshots() - 1;
			if (Math.max(currMin, deltaPair.min()) <= Math.min(currMax, deltaPair.max())) {
				currMin = Math.max(currMin, deltaPair.min());
				currMax = Math.min(currMax, deltaPair.max());
//				currSatisfyingAttrValues.add(satisfyingPairsSet.get(index));
			}
		}
		for (Entry<Pair, ArrayList<TreeSet<Pair>>> deltaToPairsEntry : deltaToPairsMap.entrySet()) {
			for (TreeSet<Pair> satisfyingPairSet : deltaToPairsEntry.getValue()) {
				for (Pair satisfyingPair : satisfyingPairSet) {
					if (satisfyingPair.max() - satisfyingPair.min() >= currMin && satisfyingPair.max() - satisfyingPair.min() <= currMax) {
						currSatisfyingAttrValues.add(new Pair(satisfyingPair.min(), satisfyingPair.max()));
					}
				}
			}
		}
		intersections.putIfAbsent(new Pair(currMin, currMax), currSatisfyingAttrValues);

		ArrayList<Entry<Pair, ArrayList<Pair>>> sortedIntersections = new ArrayList<>(intersections.entrySet());
		sortedIntersections.sort(new Comparator<Entry<Pair, ArrayList<Pair>>>() {
			@Override
			public int compare(Entry<Pair, ArrayList<Pair>> o1, Entry<Pair, ArrayList<Pair>> o2) {
				return o2.getValue().size() - o1.getValue().size();
			}
		});

		System.out.println("Candidate deltas for general TGFD:");
		for (Entry<Pair, ArrayList<Pair>> intersection : sortedIntersections) {
			System.out.println(intersection.getKey());
		}

		System.out.println("Evaluating candidate deltas for general TGFD...");
		for (Entry<Pair, ArrayList<Pair>> intersection : sortedIntersections) {
			Pair candidateDelta = intersection.getKey();
			int generalMin = candidateDelta.min();
			int generalMax = candidateDelta.max();
			System.out.println("Calculating support for candidate general TGFD candidate delta: " + intersection.getKey());

			// Compute general support
			int numberOfSatisfyingPairs = intersection.getValue().size();

			System.out.println("Number of satisfying pairs: " + numberOfSatisfyingPairs);
			System.out.println("Satisfying pairs: " + intersection.getValue());
			double support = this.calculateSupport(numberOfSatisfyingPairs, entitiesSize);
			System.out.println("Candidate general TGFD support: " + support);
			this.generalTgfdSupportsList.add(support);

			Delta delta = new Delta(Period.ofYears(generalMin), Period.ofYears(generalMax), Duration.ofDays(365));

			Dependency generalDependency = new Dependency();
			String yVertexType = literalPath.getRhs().getVertexType();
			String yAttrName = literalPath.getRhs().getAttrName();
			VariableLiteral y = new VariableLiteral(yVertexType, yAttrName, yVertexType, yAttrName);
			generalDependency.addLiteralToY(y);
			for (ConstantLiteral x : literalPath.getLhs()) {
				String xVertexType = x.getVertexType();
				String xAttrName = x.getAttrName();
				VariableLiteral varX = new VariableLiteral(xVertexType, xAttrName, xVertexType, xAttrName);
				generalDependency.addLiteralToX(varX);
			}

			if (support < this.getTgfdTheta()) {
				if (this.hasSupportPruning() && delta.getMin().getYears() == 0 && delta.getMax().getYears() == this.getNumOfSnapshots()-1) {
					literalTreeNode.setIsPruned();
					patternTreeNode.addLowSupportDependency(new AttributeDependency(literalPath.getLhs(), literalPath.getRhs(), delta));
				}
				System.out.println("Support for candidate general TGFD is below support threshold");
//				continue;
			} else {
				System.out.println("Creating new general TGFD...");
				TGFD tgfd = new TGFD(patternTreeNode.getPattern(), delta, generalDependency, support, patternSupport, "");
				System.out.println("TGFD: " + tgfd);
				tgfds.add(tgfd);
			}
		}
		return tgfds;
	}

	private boolean isSupersetPath(AttributeDependency literalPath, Pair candidateDelta, HashMap<AttributeDependency, ArrayList<Pair>> lowSupportGeneralTgfdList) {
		for (Map.Entry<AttributeDependency, ArrayList<Pair>> lowSupportGeneralTgfdEntry: lowSupportGeneralTgfdList.entrySet()) {
			AttributeDependency lowSupportGeneralTgfdDependencyPath = lowSupportGeneralTgfdEntry.getKey();
			ArrayList<Pair> lowSupportGeneralTgfdDeltaPairList = lowSupportGeneralTgfdEntry.getValue();
			for (Pair lowSupportGeneralTgfdDeltaPair : lowSupportGeneralTgfdDeltaPairList) {
				if (literalPath.getRhs().equals(lowSupportGeneralTgfdDependencyPath.getRhs()) && literalPath.getLhs().containsAll(lowSupportGeneralTgfdDependencyPath.getLhs())) {
					System.out.println("Candidate path " + literalPath + " is a superset of pruned path " + lowSupportGeneralTgfdDependencyPath);
					if (candidateDelta.min() >= lowSupportGeneralTgfdDeltaPair.min() &&  candidateDelta.max() <= lowSupportGeneralTgfdDeltaPair.max()) {
						System.out.println("The candidate general dependency " + literalPath
								+ "\n with candidate delta " + candidateDelta
								+ "\n is a superset of a minimal dependency " + lowSupportGeneralTgfdDependencyPath
								+ "\n with minimal delta " + lowSupportGeneralTgfdDeltaPair
								+ ".");
						return true;
					}
				}
			}
		}
		return false;
	}

	private ArrayList<TGFD> discoverConstantTGFDs(PatternTreeNode patternNode, ConstantLiteral yLiteral, Map<Set<ConstantLiteral>, ArrayList<Entry<ConstantLiteral, List<Integer>>>> entities, Map<Pair, ArrayList<TreeSet<Pair>>> deltaToPairsMap) {
		ArrayList<TGFD> tgfds = new ArrayList<>();
		String yVertexType = yLiteral.getVertexType();
		String yAttrName = yLiteral.getAttrName();
		for (Entry<Set<ConstantLiteral>, ArrayList<Entry<ConstantLiteral, List<Integer>>>> entityEntry : entities.entrySet()) {
			VF2PatternGraph newPattern = patternNode.getPattern().copy();
			Dependency newDependency = new Dependency();
			AttributeDependency constantPath = new AttributeDependency();
			for (Vertex v : newPattern.getPattern().vertexSet()) {
				String vType = new ArrayList<>(v.getTypes()).get(0);
				if (vType.equalsIgnoreCase(yVertexType)) { // TO-DO: What if our pattern has duplicate vertex types?
					v.putAttributeIfAbsent(new Attribute(yAttrName));
					if (newDependency.getY().size() == 0) {
						VariableLiteral newY = new VariableLiteral(yVertexType, yAttrName, yVertexType, yAttrName);
						newDependency.addLiteralToY(newY);
					}
				}
				for (ConstantLiteral xLiteral : entityEntry.getKey()) {
					if (xLiteral.getVertexType().equalsIgnoreCase(vType)) {
						v.putAttributeIfAbsent(new Attribute(xLiteral.getAttrName(), xLiteral.getAttrValue()));
						ConstantLiteral newXLiteral = new ConstantLiteral(vType, xLiteral.getAttrName(), xLiteral.getAttrValue());
						newDependency.addLiteralToX(newXLiteral);
						constantPath.addToLhs(newXLiteral);
					}
				}
			}
			constantPath.setRhs(new ConstantLiteral(yVertexType, yAttrName, null));

			System.out.println("Performing Constant TGFD discovery");
			System.out.println("Pattern: " + newPattern);
			System.out.println("Entity: " + newDependency);

			System.out.println("Candidate RHS values for entity...");
			ArrayList<Entry<ConstantLiteral, List<Integer>>> attrValuesTimestampsSortedByFreq = entityEntry.getValue();
			for (Map.Entry<ConstantLiteral, List<Integer>> entry : attrValuesTimestampsSortedByFreq) {
				System.out.println(entry.getKey() + ":" + entry.getValue());
			}

            System.out.println("Computing candidate delta for RHS value...\n" + attrValuesTimestampsSortedByFreq.get(0).getKey());
            ArrayList<Pair> candidateDeltas = new ArrayList<>();
            if (attrValuesTimestampsSortedByFreq.size() == 1) {
				List<Integer> timestamps = attrValuesTimestampsSortedByFreq.get(0).getValue();
				int minDistance = this.getNumOfSnapshots() - 1;
				int maxDistance = timestamps.get(timestamps.size() - 1) - timestamps.get(0);
				for (int index = 1; index < timestamps.size(); index++) {
					minDistance = Math.min(minDistance, timestamps.get(index) - timestamps.get(index - 1));
				}
				if (minDistance > maxDistance) {
					System.out.println("Not enough timestamped matches found for entity.");
					continue;
				}
				candidateDeltas.add(new Pair(minDistance, maxDistance));
			} else if (attrValuesTimestampsSortedByFreq.size() > 1) {
				List<Integer> mostFreqTimestamps = attrValuesTimestampsSortedByFreq.get(0).getValue();
				int minExclusionDistance = this.getNumOfSnapshots() - 1;
				int maxExclusionDistance = 0;
				for (Map.Entry<ConstantLiteral, List<Integer>> otherTimestamps : attrValuesTimestampsSortedByFreq.subList(1,attrValuesTimestampsSortedByFreq.size())) {
					for (Integer timestamp: otherTimestamps.getValue()) {
						for (Integer refTimestamp: mostFreqTimestamps) {
							int distance = Math.abs(timestamp - refTimestamp);
							minExclusionDistance = Math.min(minExclusionDistance, distance);
							maxExclusionDistance = Math.max(maxExclusionDistance, distance);
						}
					}
					if (minExclusionDistance == 0 && maxExclusionDistance == (this.getNumOfSnapshots() -1)) break;
				}
				if (minExclusionDistance > 0) {
					candidateDeltas.add(new Pair(0, minExclusionDistance-1));
				}
				if (maxExclusionDistance < this.getNumOfSnapshots() -1) {
					candidateDeltas.add(new Pair(maxExclusionDistance+1, this.getNumOfSnapshots() -1));
				}
			}
			if (candidateDeltas.size() == 0) {
				System.out.println("Could not find any deltas for entity: " + entityEntry.getKey());
				continue;
			}

			// Compute TGFD support
			Delta candidateTGFDdelta;
			double candidateTGFDsupport = 0;
			Pair mostSupportedDelta = null;
			TreeSet<Pair> mostSupportedSatisfyingPairs = null;
			for (Pair candidateDelta : candidateDeltas) {
				int minDistance = candidateDelta.min();
				int maxDistance = candidateDelta.max();
				if (minDistance <= maxDistance) {
					System.out.println("Calculating support for candidate delta ("+minDistance+","+maxDistance+")");
					double numerator;
					List<Integer> timestamps = attrValuesTimestampsSortedByFreq.get(0).getValue();
					TreeSet<Pair> satisfyingPairs = new TreeSet<>();
					for (int index = 0; index < timestamps.size() - 1; index++) {
						for (int j = index + 1; j < timestamps.size(); j++) {
							if (timestamps.get(j) - timestamps.get(index) >= minDistance && timestamps.get(j) - timestamps.get(index) <= maxDistance) {
								satisfyingPairs.add(new Pair(timestamps.get(index), timestamps.get(j)));
							}
						}
					}

					System.out.println("Satisfying pairs: " + satisfyingPairs);

					numerator = satisfyingPairs.size();
					double candidateSupport = this.calculateSupport(numerator, entities.size());

					if (candidateSupport > candidateTGFDsupport) {
						candidateTGFDsupport = candidateSupport;
						mostSupportedDelta = candidateDelta;
						mostSupportedSatisfyingPairs = satisfyingPairs;
					}
				}
			}
			if (mostSupportedDelta == null) {
				System.out.println("Could not come up with mostSupportedDelta for entity: " + entityEntry.getKey());
				continue;
			}
			System.out.println("Entity satisfying attributes:" + mostSupportedSatisfyingPairs);
			System.out.println("Entity delta = " + mostSupportedDelta);
			System.out.println("Entity support = " + candidateTGFDsupport);

			// All entities are considered in general TGFD, regardless of their support
			if (!deltaToPairsMap.containsKey(mostSupportedDelta)) {
				deltaToPairsMap.put(mostSupportedDelta, new ArrayList<>());
			}
			deltaToPairsMap.get(mostSupportedDelta).add(mostSupportedSatisfyingPairs);

			this.constantTgfdSupportsList.add(candidateTGFDsupport); // Statistics

			int minDistance = mostSupportedDelta.min();
			int maxDistance = mostSupportedDelta.max();
			candidateTGFDdelta = new Delta(Period.ofYears(minDistance), Period.ofYears(maxDistance), Duration.ofDays(365));
			System.out.println("Constant TGFD delta: "+candidateTGFDdelta);
			constantPath.setDelta(candidateTGFDdelta);

			if (this.hasMinimalityPruning() && constantPath.isSuperSetOfPathAndSubsetOfDelta(patternNode.getAllMinimalConstantDependenciesOnThisPath())) { // Ensures we don't expand constant TGFDs from previous iterations
				System.out.println("Candidate constant TGFD " + constantPath + " is a superset of an existing minimal constant TGFD");
				continue;
			}
			// Only output constant TGFDs that satisfy support
			if (candidateTGFDsupport < this.getTgfdTheta()) {
				if (this.hasSupportPruning() && candidateTGFDdelta.getMin().getYears() == 0 && candidateTGFDdelta.getMax().getYears() == this.getNumOfSnapshots()-1)
					patternNode.addLowSupportDependency(new AttributeDependency(constantPath.getLhs(), constantPath.getRhs(), candidateTGFDdelta));
				System.out.println("Could not satisfy TGFD support threshold for entity: " + entityEntry.getKey());
//				continue;
			} else {
				System.out.println("Creating new constant TGFD...");
				TGFD entityTGFD = new TGFD(newPattern, candidateTGFDdelta, newDependency, candidateTGFDsupport, patternNode.getPatternSupport(), "");
				System.out.println("TGFD: " + entityTGFD);
				tgfds.add(entityTGFD);
				if (this.hasMinimalityPruning()) patternNode.addMinimalConstantDependency(constantPath);
			}
		}
		return tgfds;
	}

	public ArrayList<TGFD> getDummyVertexTypeTGFDs() {
		ArrayList<TGFD> dummyTGFDs = new ArrayList<>();
		for (Map.Entry<String,Integer> frequentVertexTypeEntry : this.getSortedVertexHistogram()) {
			String frequentVertexType = frequentVertexTypeEntry.getKey();
			VF2PatternGraph patternGraph = new VF2PatternGraph();
			PatternVertex patternVertex = new PatternVertex(frequentVertexType);
			patternGraph.addVertex(patternVertex);
//			HashSet<ConstantLiteral> activeAttributes = getActiveAttributesInPattern(patternGraph.getPattern().vertexSet());
//			for (ConstantLiteral activeAttribute: activeAttributes) {
				TGFD dummyTGFD = new TGFD();
				dummyTGFD.setName(frequentVertexType);
				dummyTGFD.setPattern(patternGraph);
//				Dependency dependency = new Dependency();
//				dependency.addLiteralToY(activeAttribute);
				dummyTGFDs.add(dummyTGFD);
//			}
		}
		return dummyTGFDs;
	}

	public ArrayList<TGFD> getDummyEdgeTypeTGFDs() {
		ArrayList<TGFD> dummyTGFDs = new ArrayList<>();

		for (Map.Entry<String,Integer> frequentEdgeEntry : this.sortedFrequentEdgesHistogram) {
			String frequentEdge = frequentEdgeEntry.getKey();
			String[] info = frequentEdge.split(" ");
			String sourceVertexType = info[0];
			String edgeType = info[1];
			String targetVertexType = info[2];
			VF2PatternGraph patternGraph = new VF2PatternGraph();
			PatternVertex sourceVertex = new PatternVertex(sourceVertexType);
			patternGraph.addVertex(sourceVertex);
			PatternVertex targetVertex = new PatternVertex(targetVertexType);
			patternGraph.addVertex(targetVertex);
			RelationshipEdge edge = new RelationshipEdge(edgeType);
			patternGraph.addEdge(sourceVertex, targetVertex, edge);
//			HashSet<ConstantLiteral> activeAttributes = getActiveAttributesInPattern(patternGraph.getPattern().vertexSet());
//			for (ConstantLiteral activeAttribute: activeAttributes) {
				TGFD dummyTGFD = new TGFD();
				dummyTGFD.setName(frequentEdge.replaceAll(" ","_"));
				dummyTGFD.setPattern(patternGraph);
//				Dependency dependency = new Dependency();
//				dependency.addLiteralToY(activeAttribute);
				dummyTGFDs.add(dummyTGFD);
//			}
		}
		return dummyTGFDs;
	}

	public void setImdbTimestampToFilesMapFromPath(String path) {
		HashMap<String, List<String>> timestampToFilesMap = generateImdbTimestampToFilesMapFromPath(path);
		this.setTimestampToFilesMap(new ArrayList<>(timestampToFilesMap.entrySet()));
	}

	@NotNull
	public static HashMap<String, List<String>> generateImdbTimestampToFilesMapFromPath(String path) {
		System.out.println("Searching for IMDB snapshots in path: "+ path);
		List<File> allFilesInDirectory = new ArrayList<>(List.of(Objects.requireNonNull(new File(path).listFiles(File::isFile))));
		System.out.println("Found files: "+allFilesInDirectory);
		List<File> ntFilesInDirectory = new ArrayList<>();
		for (File ntFile: allFilesInDirectory) {
			System.out.println("Is this an .nt file? "+ntFile.getName());
			if (ntFile.getName().endsWith(".nt")) {
				System.out.println("Found .nt file: "+ntFile.getPath());
				ntFilesInDirectory.add(ntFile);
			}
		}
		ntFilesInDirectory.sort(Comparator.comparing(File::getName));
		System.out.println("Found .nt files: "+ntFilesInDirectory);
		HashMap<String,List<String>> timestampToFilesMap = new HashMap<>();
		for (File ntFile: ntFilesInDirectory) {
			String regex = "^imdb-([0-9]+)\\.nt$";
			Pattern pattern = Pattern.compile(regex);
			Matcher matcher = pattern.matcher(ntFile.getName());
			if (matcher.find()) {
				String timestamp = matcher.group(1);
				timestampToFilesMap.putIfAbsent(timestamp, new ArrayList<>());
				timestampToFilesMap.get(timestamp).add(ntFile.getPath());
			}
		}
		System.out.println("TimestampToFilesMap...");
		System.out.println(timestampToFilesMap);
		return timestampToFilesMap;
	}

	protected void loadChangeFilesIntoMemory() {
		HashMap<String, org.json.simple.JSONArray> changeFilesMap = new HashMap<>();
		if (this.isUseTypeChangeFile()) {
			for (Map.Entry<String,Integer> frequentVertexTypeEntry : this.vertexHistogram.entrySet()) {
				for (int i = 0; i < this.getTimestampToFilesMap().size() - 1; i++) {
					System.out.println("-----------Snapshot (" + (i + 2) + ")-----------");
					String changeFilePath = "changes_t" + (i + 1) + "_t" + (i + 2) + "_" + frequentVertexTypeEntry.getKey() + ".json";
					JSONArray jsonArray = readJsonArrayFromFile(changeFilePath);
					System.out.println("Storing " + changeFilePath + " in memory");
					changeFilesMap.put(changeFilePath, jsonArray);
				}
			}
		} else {
			for (int i = 0; i < this.getTimestampToFilesMap().size() - 1; i++) {
				System.out.println("-----------Snapshot (" + (i + 2) + ")-----------");
				String changeFilePath = "changes_t" + (i + 1) + "_t" + (i + 2) + "_" + this.getGraphSize() + ".json";
				JSONArray jsonArray = readJsonArrayFromFile(changeFilePath);
				System.out.println("Storing " + changeFilePath + " in memory");
				changeFilesMap.put(changeFilePath, jsonArray);
			}
		}
		this.setChangeFilesMap(changeFilesMap);
	}

	private JSONArray readJsonArrayFromFile(String changeFilePath) {
		System.out.println("Reading JSON array from file "+changeFilePath);
		JSONParser parser = new JSONParser();
		Object json;
		JSONArray jsonArray = new JSONArray();
		try {
			json = parser.parse(new FileReader(changeFilePath));
			jsonArray = (JSONArray) json;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return jsonArray;
	}

	public void setDBpediaTimestampsAndFilePaths(String path) {
		Map<String, List<String>> timestampToFilesMap = generateDbpediaTimestampToFilesMap(path);
		this.setTimestampToFilesMap(new ArrayList<>(timestampToFilesMap.entrySet()));
	}

	@NotNull
	public static Map<String, List<String>> generateDbpediaTimestampToFilesMap(String path) {
		ArrayList<File> directories = new ArrayList<>(List.of(Objects.requireNonNull(new File(path).listFiles(File::isDirectory))));
		directories.sort(Comparator.comparing(File::getName));
		Map<String, List<String>> timestampToFilesMap = new HashMap<>();
		for (File directory: directories) {
			ArrayList<File> files = new ArrayList<>(List.of(Objects.requireNonNull(new File(directory.getPath()).listFiles(File::isFile))));
			List<String> paths = files.stream().map(File::getPath).collect(Collectors.toList());
			timestampToFilesMap.put(directory.getName(),paths);
		}
		return timestampToFilesMap;
	}

	public void setCitationTimestampsAndFilePaths() {
		ArrayList<String> filePaths = new ArrayList<>();
		filePaths.add("dblp_papers_v11.txt");
		filePaths.add("dblp.v12.json");
		filePaths.add("dblpv13.json");
		Map<String,List<String>> timestampstoFilePathsMap = new HashMap<>();
		int timestampName = 11;
		for (String filePath: filePaths) {
			timestampstoFilePathsMap.put(String.valueOf(timestampName), Collections.singletonList(filePath));
		}
		this.setTimestampToFilesMap(new ArrayList<>(timestampstoFilePathsMap.entrySet()));
	}

	public ArrayList<TGFD> hSpawn(PatternTreeNode patternTreeNode, List<Set<Set<ConstantLiteral>>> matchesPerTimestamps) {
		ArrayList<TGFD> tgfds = new ArrayList<>();

		System.out.println("Performing HSpawn for " + patternTreeNode.getPattern());

		HashSet<ConstantLiteral> activeAttributes = getActiveAttributesInPattern(patternTreeNode.getGraph().vertexSet(), false);

		LiteralTree literalTree = new LiteralTree();
		int hSpawnLimit = Math.max(patternTreeNode.getGraph().vertexSet().size(), this.getMaxNumOfLiterals());
		for (int j = 0; j < hSpawnLimit; j++) {

			System.out.println("HSpawn level " + j + "/" + hSpawnLimit);

			if (j == 0) {
				literalTree.addLevel();
				for (ConstantLiteral literal: activeAttributes) {
					literalTree.createNodeAtLevel(j, literal, null);
				}
			} else {
				ArrayList<LiteralTreeNode> literalTreePreviousLevel = literalTree.getLevel(j - 1);
				if (literalTreePreviousLevel.size() == 0) {
					System.out.println("Previous level of literal tree is empty. Nothing to expand. End HSpawn");
					break;
				}
				literalTree.addLevel();
				HashSet<AttributeDependency> visitedPaths = new HashSet<>(); //TO-DO: Can this be implemented as HashSet to improve performance?
				ArrayList<TGFD> currentLevelTGFDs = new ArrayList<>();
				for (LiteralTreeNode previousLevelLiteral : literalTreePreviousLevel) {
					System.out.println("Creating literal tree node " + literalTree.getLevel(j).size() + "/" + (literalTreePreviousLevel.size() * (literalTreePreviousLevel.size()-1)));
					if (previousLevelLiteral.isPruned()) {
						System.out.println("Could not expand pruned literal path "+previousLevelLiteral.getPathToRoot());
						continue;
					}
					for (ConstantLiteral literal: activeAttributes) {
						ArrayList<ConstantLiteral> parentsPathToRoot = previousLevelLiteral.getPathToRoot(); //TO-DO: Can this be implemented as HashSet to improve performance?
						if (this.isOnlyInterestingTGFDs() && j < patternTreeNode.getGraph().vertexSet().size()) { // Ensures all vertices are involved in dependency
							if (isUsedVertexType(literal.getVertexType(), parentsPathToRoot)) continue;
						}

						if (parentsPathToRoot.contains(literal)) continue;

						// Check if path to candidate leaf node is unique
						AttributeDependency newPath = new AttributeDependency(previousLevelLiteral.getPathToRoot(),literal);
						System.out.println("New candidate literal path: " + newPath);
						if (isPathVisited(newPath, visitedPaths)) { // TO-DO: Is this relevant anymore?
							System.out.println("Skip. Duplicate literal path.");
							continue;
						}

						long supersetPathCheckingTime = System.currentTimeMillis();
						if (this.hasSupportPruning() && newPath.isSuperSetOfPath(patternTreeNode.getZeroEntityDependenciesOnThisPath())) { // Ensures we don't re-explore dependencies whose subsets have no entities
							System.out.println("Skip. Candidate literal path is a superset of zero-entity dependency.");
							continue;
						}
						if (this.hasSupportPruning() && newPath.isSuperSetOfPath(patternTreeNode.getLowSupportDependenciesOnThisPath())) { // Ensures we don't re-explore dependencies whose subsets have no entities
							System.out.println("Skip. Candidate literal path is a superset of low-support dependency.");
							continue;
						}
						if (this.hasMinimalityPruning() && newPath.isSuperSetOfPath(patternTreeNode.getAllMinimalDependenciesOnThisPath())) { // Ensures we don't re-explore dependencies whose subsets have already have a general dependency
							System.out.println("Skip. Candidate literal path is a superset of minimal dependency.");
							continue;
						}
						supersetPathCheckingTime = System.currentTimeMillis()-supersetPathCheckingTime;
						printWithTime("supersetPathCheckingTime", supersetPathCheckingTime);
						setTotalSupersetPathCheckingTime(getTotalSupersetPathCheckingTime() + supersetPathCheckingTime);

						System.out.println("Newly created unique literal path: " + newPath);

						// Add leaf node to tree
						LiteralTreeNode literalTreeNode = literalTree.createNodeAtLevel(j, literal, previousLevelLiteral);

						visitedPaths.add(newPath);

						if (this.isOnlyInterestingTGFDs()) { // Ensures all vertices are involved in dependency
                            if (literalPathIsMissingTypesInPattern(literalTreeNode.getPathToRoot(), patternTreeNode.getGraph().vertexSet()))
								continue;
						}

						System.out.println("Performing Delta Discovery at HSpawn level " + j);
						final long deltaDiscoveryTime = System.currentTimeMillis();
						ArrayList<TGFD> discoveredTGFDs = deltaDiscovery(patternTreeNode, literalTreeNode, newPath, matchesPerTimestamps);
						printWithTime("deltaDiscovery", System.currentTimeMillis()-deltaDiscoveryTime);
						currentLevelTGFDs.addAll(discoveredTGFDs);
					}
				}
				System.out.println("TGFDs generated at HSpawn level " + j + ": " + currentLevelTGFDs.size());
				if (currentLevelTGFDs.size() > 0) {
					tgfds.addAll(currentLevelTGFDs);
				}
			}
			System.out.println("Generated new literal tree nodes: "+ literalTree.getLevel(j).size());
		}
		System.out.println("For pattern " + patternTreeNode.getPattern());
		System.out.println("HSpawn TGFD count: " + tgfds.size());
		return tgfds;
	}

	private static boolean isUsedVertexType(String vertexType, ArrayList<ConstantLiteral> parentsPathToRoot) {
		for (ConstantLiteral literal : parentsPathToRoot) {
			if (literal.getVertexType().equals(vertexType)) {
				return true;
			}
		}
		return false;
	}

	private static boolean literalPathIsMissingTypesInPattern(ArrayList<ConstantLiteral> parentsPathToRoot, Set<Vertex> patternVertexSet) {
		for (Vertex v : patternVertexSet) {
			boolean missingType = true;
			for (ConstantLiteral literal : parentsPathToRoot) {
				if (literal.getVertexType().equals(v.getTypes().iterator().next())) {
					missingType = false;
				}
			}
			if (missingType) return true;
		}
		return false;
	}

	public int getCurrentVSpawnLevel() {
		return currentVSpawnLevel;
	}

	public void setCurrentVSpawnLevel(int currentVSpawnLevel) {
		this.currentVSpawnLevel = currentVSpawnLevel;
	}

	public int getK() {
		return k;
	}

	public int getNumOfSnapshots() {
		return numOfSnapshots;
	}

	public double getTgfdTheta() {
		return tgfdTheta;
	}

	public long getTotalVSpawnTime() {
		return totalVSpawnTime;
	}

	public void addToTotalVSpawnTime(long vSpawnTime) {
		this.totalVSpawnTime += vSpawnTime;
	}

	public long getTotalMatchingTime() {
		return totalMatchingTime;
	}

	public void addToTotalMatchingTime(long matchingTime) {
		this.totalMatchingTime += matchingTime;
	}

	public boolean hasSupportPruning() {
		return hasSupportPruning;
	}

	public void setSupportPruning(boolean hasSupportPruning) {
		this.hasSupportPruning = hasSupportPruning;
	}

	public boolean isSkipK1() {
		return skipK1;
	}

	public ArrayList<ArrayList<TGFD>> getTgfds() {
		return tgfds;
	}

	public void initializeTgfdLists() {
		this.tgfds = new ArrayList<>();
		for (int vSpawnLevel = 0; vSpawnLevel <= this.getK(); vSpawnLevel++) {
			getTgfds().add(new ArrayList<>());
		}
	}

	public boolean reUseMatches() {
		return reUseMatches;
	}

	public boolean isGeneratek0Tgfds() {
		return generatek0Tgfds;
	}

	public boolean useChangeFile() {
		return useChangeFile;
	}

	public void setUseChangeFile(boolean useChangeFile) {
		this.useChangeFile = useChangeFile;
	}

	public int getPreviousLevelNodeIndex() {
		return previousLevelNodeIndex;
	}

	public void setPreviousLevelNodeIndex(int previousLevelNodeIndex) {
		this.previousLevelNodeIndex = previousLevelNodeIndex;
	}

	public int getCandidateEdgeIndex() {
		return candidateEdgeIndex;
	}

	public void setCandidateEdgeIndex(int candidateEdgeIndex) {
		this.candidateEdgeIndex = candidateEdgeIndex;
	}

	public void findAndSetNumOfSnapshots() {
		if (this.useChangeFile()) {
			this.setNumOfSnapshots(this.getTimestampToFilesMap().size());
		} else {
			this.setNumOfSnapshots(this.getGraphs().size());
		}
		System.out.println("Number of "+this.getLoader()+" snapshots: "+this.getNumOfSnapshots());
	}

	public String getLoader() {
		return loader;
	}

	public void setLoader(String loader) {
		this.loader = loader;
	}

	public List<Entry<String, List<String>>> getTimestampToFilesMap() {
		return timestampToFilesMap;
	}

	public void setTimestampToFilesMap(List<Entry<String, List<String>>> timestampToFilesMap) {
		timestampToFilesMap.sort(Entry.comparingByKey());
		this.timestampToFilesMap = timestampToFilesMap.subList(0,Math.min(timestampToFilesMap.size(),this.getT()));
	}

	public int getT() {
		return t;
	}

	public void setT(int t) {
		this.t = t;
	}


	public boolean isValidationSearch() {
		return validationSearch;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public void setReUseMatches(boolean reUseMatches) {
		this.reUseMatches = reUseMatches;
	}

	public void setValidationSearch(boolean validationSearch) {
		this.validationSearch = validationSearch;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public boolean isDissolveSuperVertexTypes() {
		return dissolveSuperVertexTypes;
	}

	public void setDissolveSuperVertexTypes(boolean dissolveSuperVertexTypes) {
		this.dissolveSuperVertexTypes = dissolveSuperVertexTypes;
	}

	public boolean hasMinimalityPruning() {
		return hasMinimalityPruning;
	}

	public void setMinimalityPruning(boolean hasMinimalityPruning) {
		this.hasMinimalityPruning = hasMinimalityPruning;
	}

	public void setGeneratek0Tgfds(boolean generatek0Tgfds) {
		this.generatek0Tgfds = generatek0Tgfds;
	}

	public void setSkipK1(boolean skipK1) {
		this.skipK1 = skipK1;
	}

	public boolean isOnlyInterestingTGFDs() {
		return onlyInterestingTGFDs;
	}

	public void setOnlyInterestingTGFDs(boolean onlyInterestingTGFDs) {
		this.onlyInterestingTGFDs = onlyInterestingTGFDs;
	}

	public boolean iskExperiment() {
		return kExperiment;
	}

	public void setkExperiment(boolean kExperiment) {
		this.kExperiment = kExperiment;
	}

	public List<GraphLoader> getGraphs() {
		return graphs;
	}

	public void setGraphs(List<GraphLoader> graphs) {
		this.graphs = graphs;
	}

	public boolean isStoreInMemory() {
		return isStoreInMemory;
	}

	public void setStoreInMemory(boolean storeInMemory) {
		isStoreInMemory = storeInMemory;
	}

	public String getExperimentName() {
		return experimentName;
	}

	public void setExperimentName(String experimentName) {
		this.experimentName = experimentName;
	}

	public void setK(int k) {
		this.k = k;
	}

	public void setTgfdTheta(double tgfdTheta) {
		this.tgfdTheta = tgfdTheta;
	}

	public int getGamma() {
		return gamma;
	}

	public void setGamma(int gamma) {
		this.gamma = gamma;
	}

	public int getFrequentSetSize() {
		return frequentSetSize;
	}

	public void setFrequentSetSize(int frequentSetSize) {
		this.frequentSetSize = frequentSetSize;
	}

	public void setNumOfSnapshots(int numOfSnapshots) {
		this.numOfSnapshots = numOfSnapshots;
	}

	public HashMap<String, JSONArray> getChangeFilesMap() {
		return changeFilesMap;
	}

	public void setChangeFilesMap(HashMap<String, JSONArray> changeFilesMap) {
		this.changeFilesMap = changeFilesMap;
	}

	public String getGraphSize() {
		return graphSize;
	}

	public void setGraphSize(String graphSize) {
		this.graphSize = graphSize;
	}

	public ArrayList<Long> getkRuntimes() {
		return kRuntimes;
	}

	public String getTimeAndDateStamp() {
		return timeAndDateStamp;
	}

	public void setTimeAndDateStamp(String timeAndDateStamp) {
		this.timeAndDateStamp = timeAndDateStamp;
	}

	public boolean isUseTypeChangeFile() {
		return useTypeChangeFile;
	}

	public void setUseTypeChangeFile(boolean useTypeChangeFile) {
		this.useTypeChangeFile = useTypeChangeFile;
	}

	public List<Entry<String, Integer>> getSortedVertexHistogram() {
		return sortedVertexHistogram;
	}

	public Integer getNumOfEdgesInAllGraphs() {
		return numOfEdgesInAllGraphs;
	}

	public void setNumOfEdgesInAllGraphs(Integer numOfEdgesInAllGraphs) {
		this.numOfEdgesInAllGraphs = numOfEdgesInAllGraphs;
	}

	public int getNumOfVerticesInAllGraphs() {
		return numOfVerticesInAllGraphs;
	}

	public void setNumOfVerticesInAllGraphs(int numOfVerticesInAllGraphs) {
		this.numOfVerticesInAllGraphs = numOfVerticesInAllGraphs;
	}

	public double getSuperVertexDegree() {
		return superVertexDegree;
	}

	public void setSuperVertexDegree(double superVertexDegree) {
		this.superVertexDegree = superVertexDegree;
	}

	public Map<String, Double> getVertexTypesToAvgInDegreeMap() {
		return vertexTypesToAvgInDegreeMap;
	}

	public void setVertexTypesToAvgInDegreeMap(Map<String, Double> vertexTypesToAvgInDegreeMap) {
		this.vertexTypesToAvgInDegreeMap = vertexTypesToAvgInDegreeMap;
	}

	public boolean isDissolveSuperVerticesBasedOnCount() {
		return dissolveSuperVerticesBasedOnCount;
	}

	public void setDissolveSuperVerticesBasedOnCount(boolean dissolveSuperVerticesBasedOnCount) {
		this.dissolveSuperVerticesBasedOnCount = dissolveSuperVerticesBasedOnCount;
	}

	public Model getFirstSnapshotTypeModel() {
		return firstSnapshotTypeModel;
	}

	public void setFirstSnapshotTypeModel(Model firstSnapshotTypeModel) {
		this.firstSnapshotTypeModel = firstSnapshotTypeModel;
	}

	public Model getFirstSnapshotDataModel() {
		return firstSnapshotDataModel;
	}

	public void setFirstSnapshotDataModel(Model firstSnapshotDataModel) {
		this.firstSnapshotDataModel = firstSnapshotDataModel;
	}

	public int getMaxNumOfLiterals() {
		return maxNumOfLiterals;
	}

	public void setMaxNumOfLiterals(int maxNumOfLiterals) {
		this.maxNumOfLiterals = maxNumOfLiterals;
	}

	public long getTotalVisitedPathCheckingTime() {
		return totalVisitedPathCheckingTime;
	}

	public void setTotalVisitedPathCheckingTime(long totalVisitedPathCheckingTime) {
		this.totalVisitedPathCheckingTime = totalVisitedPathCheckingTime;
	}

	public long getTotalSupersetPathCheckingTime() {
		return totalSupersetPathCheckingTime;
	}

	public void setTotalSupersetPathCheckingTime(long totalSupersetPathCheckingTime) {
		this.totalSupersetPathCheckingTime = totalSupersetPathCheckingTime;
	}

	public long getTotalFindEntitiesTime() {
		return totalFindEntitiesTime;
	}

	public void setTotalFindEntitiesTime(long totalFindEntitiesTime) {
		this.totalFindEntitiesTime = totalFindEntitiesTime;
	}

	public long getTotalDiscoverConstantTGFDsTime() {
		return totalDiscoverConstantTGFDsTime;
	}

	public void setTotalDiscoverConstantTGFDsTime(long totalDiscoverConstantTGFDsTime) {
		this.totalDiscoverConstantTGFDsTime = totalDiscoverConstantTGFDsTime;
	}

	public long getTotalDiscoverGeneralTGFDTime() {
		return totalDiscoverGeneralTGFDTime;
	}

	public void setTotalDiscoverGeneralTGFDTime(long totalDiscoverGeneralTGFDTime) {
		this.totalDiscoverGeneralTGFDTime = totalDiscoverGeneralTGFDTime;
	}

	public double getPatternTheta() {
		return patternTheta;
	}

	public void setPatternTheta(double patternTheta) {
		this.patternTheta = patternTheta;
	}

	public static class Pair implements Comparable<Pair> {
		private final Integer min;
		private final Integer max;

		public Pair(int min, int max) {
			this.min = min;
			this.max = max;
		}

		public Integer min() {
			return min;
		}

		public Integer max() {
			return max;
		}

		@Override
		public int compareTo(@NotNull TgfdDiscovery.Pair o) {
			if (this.min.equals(o.min)) {
				return this.max.compareTo(o.max);
			} else {
				return this.min.compareTo(o.min);
			}
		}

		@Override
		public String toString() {
			return "(" + min +
					", " + max +
					')';
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Pair pair = (Pair) o;
			return min.equals(pair.min) && max.equals(pair.max);
		}

		@Override
		public int hashCode() {
			return Objects.hash(min, max);
		}
	}

	public void vSpawnInit(List<GraphLoader> graphs) {
		this.patternTree = new PatternTree();
		this.patternTree.addLevel();

		System.out.println("VSpawn Level 0");
		for (int i = 0; i < this.getSortedVertexHistogram().size(); i++) {
			System.out.println("VSpawnInit with single-node pattern " + (i+1) + "/" + this.getSortedVertexHistogram().size());
			String vertexType = this.getSortedVertexHistogram().get(i).getKey();

			if (this.vertexTypesToAttributesMap.get(vertexType).size() == 0)
				continue; // TO-DO: Should these frequent types without active attribute be filtered out much earlier?

			int numOfInstancesOfVertexType = this.getSortedVertexHistogram().get(i).getValue();
			int numOfInstancesOfAllVertexTypes = this.getNumOfVerticesInAllGraphs();

			double frequency = (double) numOfInstancesOfVertexType / (double) numOfInstancesOfAllVertexTypes;
			System.out.println("Frequency of vertex type: " + numOfInstancesOfVertexType + " / " + numOfInstancesOfAllVertexTypes + " = " + frequency);

			System.out.println("Vertex type: "+vertexType);
			VF2PatternGraph candidatePattern = new VF2PatternGraph();
			PatternVertex vertex = new PatternVertex(vertexType);
			candidatePattern.addVertex(vertex);
			candidatePattern.getCenterVertexType();
			System.out.println("VSpawnInit with single-node pattern " + (i+1) + "/" + this.getSortedVertexHistogram().size() + ": " + candidatePattern.getPattern().vertexSet());

			PatternTreeNode patternTreeNode;
			patternTreeNode = this.patternTree.createNodeAtLevel(this.getCurrentVSpawnLevel(), candidatePattern);

//			if (!this.isGeneratek0Tgfds()) {
			final long extractListOfCenterVerticesTime = System.currentTimeMillis();
			ArrayList<ArrayList<DataVertex>> matchesOfThisCenterVertexPerTimestamp;
			if (this.reUseMatches()) {
				matchesOfThisCenterVertexPerTimestamp = extractListOfCenterVertices(graphs, vertexType);
				patternTreeNode.setListOfCenterVertices(matchesOfThisCenterVertexPerTimestamp);
				printWithTime("extractListOfCenterVerticesTime", (System.currentTimeMillis() - extractListOfCenterVerticesTime));
				int numOfMatches = 0;
				for (ArrayList<DataVertex> matchesOfThisCenterVertex: matchesOfThisCenterVertexPerTimestamp) {
					numOfMatches += matchesOfThisCenterVertex.size();
				}
				System.out.println("Number of center vertex matches found containing active attributes: " + numOfMatches);
			}

//			this.setPatternSupport(numOfMatches, patternTreeNode);
//			if (doesNotSatisfyTheta(patternTreeNode)) {
//				patternTreeNode.setIsPruned();
//			}
//			}
//			else {
//				ArrayList<ArrayList<HashSet<ConstantLiteral>>> matchesPerTimestamps = new ArrayList<>();
//				for (int year = 0; year < this.getNumOfSnapshots(); year++) {
//					matchesPerTimestamps.add(new ArrayList<>());
//				}
//				// TO-DO: Verify if this works for k = 0?
//				findMatchesUsingCenterVertices(graphs, patternTreeNode, matchesPerTimestamps);
//				if (patternTreeNode.getPatternSupport() < this.getTheta()) {
//					System.out.println("Mark as pruned. Real pattern support too low for pattern " + patternTreeNode.getPattern());
//					if (!this.hasNoSupportPruning()) patternTreeNode.setIsPruned();
//					continue;
//				}
//				final long hSpawnStartTime = System.currentTimeMillis();
//				ArrayList<TGFD> tgfds = this.hSpawn(patternTreeNode, matchesPerTimestamps);
//				printWithTime("hSpawn", (System.currentTimeMillis() - hSpawnStartTime));
//				this.getTgfds().get(0).addAll(tgfds);
//			}
		}
		System.out.println("GenTree Level " + this.getCurrentVSpawnLevel() + " size: " + this.patternTree.getLevel(this.getCurrentVSpawnLevel()).size());
		for (PatternTreeNode node : this.patternTree.getLevel(this.getCurrentVSpawnLevel())) {
			System.out.println("Pattern: " + node.getPattern());
//			System.out.println("Pattern Support: " + node.getPatternSupport());
//			System.out.println("Dependency: " + node.getDependenciesSets());
		}

	}

	public void setPatternSupport(Map<String, List<Integer>> entityURIsMap, PatternTreeNode patternTreeNode) {
		System.out.println("Calculating pattern support...");
		String centerVertexType = patternTreeNode.getPattern().getCenterVertexType();
		System.out.println("Center vertex type: " + centerVertexType);
		int numOfPossiblePairs = 0;
		for (Entry<String, List<Integer>> entityUriEntry : entityURIsMap.entrySet()) {
			int numberOfAcrossMatchesOfEntity = (int) entityUriEntry.getValue().stream().filter(x -> x > 0).count();
			int k = 2;
			if (numberOfAcrossMatchesOfEntity >= k) {
				numOfPossiblePairs += CombinatoricsUtils.binomialCoefficient(numberOfAcrossMatchesOfEntity, k);
			}
			int numberOfWithinMatchesOfEntity = (int) entityUriEntry.getValue().stream().filter(x -> x > 1).count();
			numOfPossiblePairs += numberOfWithinMatchesOfEntity;
		}
		int S = this.vertexHistogram.get(centerVertexType);
		double patternSupport = calculateSupport(numOfPossiblePairs, S);
		patternTreeNode.setPatternSupport(patternSupport);
		this.patternSupportsList.add(patternSupport);
	}

	private double calculateSupport(double numerator, double S) {
		System.out.println("S = "+S);
		double denominator = S * CombinatoricsUtils.binomialCoefficient(this.getNumOfSnapshots()+1,2);
		assert numerator <= denominator;
		double support = numerator / denominator;
		System.out.println("Support: " + numerator + " / " + denominator + " = " + support);
		return support;
	}

	private boolean doesNotSatisfyTheta(PatternTreeNode patternTreeNode) {
		assert patternTreeNode.getPatternSupport() != null;
		return patternTreeNode.getPatternSupport() < this.getPatternTheta();
	}

	public static boolean isDuplicateEdge(VF2PatternGraph pattern, String edgeType, String sourceType, String targetType) {
		for (RelationshipEdge edge : pattern.getPattern().edgeSet()) {
			if (edge.getLabel().equalsIgnoreCase(edgeType)) {
				if (edge.getSource().getTypes().contains(sourceType) && edge.getTarget().getTypes().contains(targetType)) {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean isMultipleEdge(VF2PatternGraph pattern, String sourceType, String targetType) {
		for (RelationshipEdge edge : pattern.getPattern().edgeSet()) {
			if (edge.getSource().getTypes().contains(sourceType) && edge.getTarget().getTypes().contains(targetType)) {
				return true;
			}
		}
		return false;
	}

	public PatternTreeNode vSpawn() {

		if (this.getCandidateEdgeIndex() > this.sortedFrequentEdgesHistogram.size()-1) {
			this.setCandidateEdgeIndex(0);
			this.setPreviousLevelNodeIndex(this.getPreviousLevelNodeIndex() + 1);
		}

		if (this.getPreviousLevelNodeIndex() >= this.patternTree.getLevel(this.getCurrentVSpawnLevel() -1).size()) {
			this.getkRuntimes().add(System.currentTimeMillis() - this.getStartTime());
			this.printTgfdsToFile(this.getExperimentName(), this.getTgfds().get(this.getCurrentVSpawnLevel()));
			if (this.iskExperiment()) this.printExperimentRuntimestoFile();
			this.printSupportStatistics();
			this.setCurrentVSpawnLevel(this.getCurrentVSpawnLevel() + 1);
			if (this.getCurrentVSpawnLevel() > this.getK()) {
				return null;
			}
			this.patternTree.addLevel();
			this.setPreviousLevelNodeIndex(0);
			this.setCandidateEdgeIndex(0);
		}

		System.out.println("Performing VSpawn");
		System.out.println("VSpawn Level " + this.getCurrentVSpawnLevel());
//		System.gc();

		ArrayList<PatternTreeNode> previousLevel = this.patternTree.getLevel(this.getCurrentVSpawnLevel() - 1);
		if (previousLevel.size() == 0) {
			this.setPreviousLevelNodeIndex(this.getPreviousLevelNodeIndex() + 1);
			return null;
		}
		PatternTreeNode previousLevelNode = previousLevel.get(this.getPreviousLevelNodeIndex());
		System.out.println("Processing previous level node " + this.getPreviousLevelNodeIndex() + "/" + previousLevel.size());
		System.out.println("Performing VSpawn on pattern: " + previousLevelNode.getPattern());

		System.out.println("Level " + (this.getCurrentVSpawnLevel() - 1) + " pattern: " + previousLevelNode.getPattern());
		if (this.hasSupportPruning() && previousLevelNode.isPruned()) {
			System.out.println("Marked as pruned. Skip.");
			this.setPreviousLevelNodeIndex(this.getPreviousLevelNodeIndex() + 1);
			return null;
		}

		System.out.println("Processing candidate edge " + this.getCandidateEdgeIndex() + "/" + this.sortedFrequentEdgesHistogram.size());
		Map.Entry<String, Integer> candidateEdge = this.sortedFrequentEdgesHistogram.get(this.getCandidateEdgeIndex());
		String candidateEdgeString = candidateEdge.getKey();
		System.out.println("Candidate edge:" + candidateEdgeString);


		String sourceVertexType = candidateEdgeString.split(" ")[0];
		String targetVertexType = candidateEdgeString.split(" ")[2];

		if (this.vertexTypesToAttributesMap.get(targetVertexType).size() == 0) {
			System.out.println("Target vertex in candidate edge does not contain active attributes");
			this.setCandidateEdgeIndex(this.getCandidateEdgeIndex() + 1);
			return null;
		}

		// TO-DO: We should add support for duplicate vertex types in the future
		if (sourceVertexType.equals(targetVertexType)) {
			System.out.println("Candidate edge contains duplicate vertex types. Skip.");
			this.setCandidateEdgeIndex(this.getCandidateEdgeIndex() + 1);
			return null;
		}
		String edgeType = candidateEdgeString.split(" ")[1];

		// Check if candidate edge already exists in pattern
		if (isDuplicateEdge(previousLevelNode.getPattern(), edgeType, sourceVertexType, targetVertexType)) {
			System.out.println("Candidate edge: " + candidateEdge.getKey());
			System.out.println("already exists in pattern");
			this.setCandidateEdgeIndex(this.getCandidateEdgeIndex() + 1);
			return null;
		}

		if (isMultipleEdge(previousLevelNode.getPattern(), sourceVertexType, targetVertexType)) {
			System.out.println("We do not support multiple edges between existing vertices.");
			this.setCandidateEdgeIndex(this.getCandidateEdgeIndex() + 1);
			return null;
		}

		// Checks if candidate edge extends pattern
		PatternVertex sourceVertex = isDuplicateVertex(previousLevelNode.getPattern(), sourceVertexType);
		PatternVertex targetVertex = isDuplicateVertex(previousLevelNode.getPattern(), targetVertexType);
		if (sourceVertex == null && targetVertex == null) {
			System.out.println("Candidate edge: " + candidateEdge.getKey());
			System.out.println("does not extend from pattern");
			this.setCandidateEdgeIndex(this.getCandidateEdgeIndex() + 1);
			return null;
		}

		PatternTreeNode patternTreeNode = null;
		// TO-DO: FIX label conflict. What if an edge has same vertex type on both sides?
		for (Vertex v : previousLevelNode.getGraph().vertexSet()) {
			System.out.println("Looking to add candidate edge to vertex: " + v.getTypes());
			PatternVertex pv = (PatternVertex) v;
			if (pv.isMarked()) {
				System.out.println("Skip vertex. Already added candidate edge to vertex: " + pv.getTypes());
				continue;
			}
			if (!pv.getTypes().contains(sourceVertexType) && !pv.getTypes().contains(targetVertexType)) {
				System.out.println("Skip vertex. Candidate edge does not connect to vertex: " + pv.getTypes());
				pv.setMarked(true);
				continue;
			}

			// Create unmarked copy of k-1 pattern
			VF2PatternGraph newPattern = previousLevelNode.getPattern().copy();
			if (targetVertex == null) {
				targetVertex = new PatternVertex(targetVertexType);
				newPattern.addVertex(targetVertex);
			} else {
				for (Vertex vertex : newPattern.getPattern().vertexSet()) {
					if (vertex.getTypes().contains(targetVertexType)) {
						targetVertex.setMarked(true);
						targetVertex = (PatternVertex) vertex;
						break;
					}
				}
			}
			RelationshipEdge newEdge = new RelationshipEdge(edgeType);
			if (sourceVertex == null) {
				sourceVertex = new PatternVertex(sourceVertexType);
				newPattern.addVertex(sourceVertex);
			} else {
				for (Vertex vertex : newPattern.getPattern().vertexSet()) {
					if (vertex.getTypes().contains(sourceVertexType)) {
						sourceVertex.setMarked(true);
						sourceVertex = (PatternVertex) vertex;
						break;
					}
				}
			}
			newPattern.addEdge(sourceVertex, targetVertex, newEdge);

			System.out.println("Created new pattern: " + newPattern);

			// TO-DO: Debug - Why does this work with strings but not subgraph isomorphism???
			if (isIsomorphicPattern(newPattern, this.patternTree)) {
				pv.setMarked(true);
				System.out.println("Skip. Candidate pattern is an isomorph of existing pattern");
				continue;
			}

			if (this.hasSupportPruning() && isSuperGraphOfPrunedPattern(newPattern, this.patternTree)) {
				pv.setMarked(true);
				System.out.println("Skip. Candidate pattern is a supergraph of pruned pattern");
				continue;
			}
			if (this.getCurrentVSpawnLevel() == 1) {
				patternTreeNode = new PatternTreeNode(newPattern, previousLevelNode, candidateEdgeString);
				for (RelationshipEdge e : newPattern.getPattern().edgeSet()) {
					String sourceType = e.getSource().getTypes().iterator().next();
					String targetType = e.getTarget().getTypes().iterator().next();
					String centerVertexType = this.getVertexTypesToAvgInDegreeMap().get(sourceType) > this.getVertexTypesToAvgInDegreeMap().get(targetType) ? sourceType : targetType;
					newPattern.setCenterVertexType(centerVertexType);
					newPattern.setRadius(1);
					newPattern.setDiameter(1);
				}
				this.patternTree.getTree().get(this.getCurrentVSpawnLevel()).add(patternTreeNode);
				this.patternTree.findSubgraphParents(this.getCurrentVSpawnLevel()-1, patternTreeNode);
				this.patternTree.findCenterVertexParent(this.getCurrentVSpawnLevel()-1, patternTreeNode);
			} else {
				int minRadius = newPattern.getPattern().vertexSet().size();
				for (Vertex newV: newPattern.getPattern().vertexSet()) {
					minRadius = Math.min(minRadius, newPattern.calculateRadiusForGivenVertex(newV));
				}
				Map<String, Double> maxDegreeTypes = new HashMap<>();
				for (Vertex newV: newPattern.getPattern().vertexSet()) {
					if (minRadius == newPattern.calculateRadiusForGivenVertex(newV)) {
						String type = newV.getTypes().iterator().next();
						maxDegreeTypes.put(type, this.getVertexTypesToAvgInDegreeMap().get(type));
					}
				}
				assert maxDegreeTypes.size() > 0;
				List<Entry<String, Double>> entries = new ArrayList<>(maxDegreeTypes.entrySet());
				entries.sort(new Comparator<Entry<String, Double>>() {
					@Override
					public int compare(Entry<String, Double> o1, Entry<String, Double> o2) {
						return o2.getValue().compareTo(o1.getValue());
					}
				});
				String centerVertexType = entries.get(0).getKey();
				newPattern.getCenterVertexType();
				newPattern.setCenterVertexType(centerVertexType);
				patternTreeNode = this.patternTree.createNodeAtLevel(this.getCurrentVSpawnLevel(), newPattern, previousLevelNode, candidateEdgeString);
			}
			System.out.println("Marking vertex " + pv.getTypes() + "as expanded.");
			break;
		}
		if (patternTreeNode == null) {
			for (Vertex v : previousLevelNode.getGraph().vertexSet()) {
				System.out.println("Unmarking all vertices in current pattern for the next candidate edge");
				((PatternVertex)v).setMarked(false);
			}
			this.setCandidateEdgeIndex(this.getCandidateEdgeIndex() + 1);
		}
		return patternTreeNode;
	}

	private boolean isIsomorphicPattern(VF2PatternGraph newPattern, PatternTree patternTree) {
		final long isIsomorphicPatternCheckTime = System.currentTimeMillis();
	    System.out.println("Checking if following pattern is isomorphic\n" + newPattern);
	    ArrayList<String> newPatternEdges = new ArrayList<>();
        newPattern.getPattern().edgeSet().forEach((edge) -> {newPatternEdges.add(edge.toString());});
        boolean isIsomorphic = false;
		for (PatternTreeNode otherPattern: patternTree.getLevel(this.getCurrentVSpawnLevel())) {
//			VF2AbstractIsomorphismInspector<Vertex, RelationshipEdge> results = new VF2SubgraphIsomorphism().execute2(newPattern.getPattern(), otherPattern.getPattern(), false);
//			if (results.isomorphismExists()) {
            ArrayList<String> otherPatternEdges = new ArrayList<>();
            otherPattern.getGraph().edgeSet().forEach((edge) -> {otherPatternEdges.add(edge.toString());});
//            if (newPattern.toString().equals(otherPattern.getPattern().toString())) {
            if (newPatternEdges.containsAll(otherPatternEdges)) {
				System.out.println("Candidate pattern: " + newPattern);
				System.out.println("is an isomorph of current VSpawn level pattern: " + otherPattern.getPattern());
				isIsomorphic = true;
			}
		}
		printWithTime("isIsomorphicPatternCheck", System.currentTimeMillis()-isIsomorphicPatternCheckTime);
		return isIsomorphic;
	}

	private boolean isSuperGraphOfPrunedPattern(VF2PatternGraph newPattern, PatternTree patternTree) {
		final long isSuperGraphOfPrunedPatternTime = System.currentTimeMillis();
        ArrayList<String> newPatternEdges = new ArrayList<>();
        newPattern.getPattern().edgeSet().forEach((edge) -> {newPatternEdges.add(edge.toString());});
		int i = this.getCurrentVSpawnLevel();
		boolean isSupergraph = false;
		while (i > 0) {
			for (PatternTreeNode otherPattern : patternTree.getLevel(i)) {
				if (otherPattern.isPruned()) {
//					VF2AbstractIsomorphismInspector<Vertex, RelationshipEdge> results = new VF2SubgraphIsomorphism().execute2(newPattern.getPattern(), otherPattern.getPattern(), false);
//			        if (results.isomorphismExists()) {
//                    if (newPattern.toString().equals(otherPattern.getPattern().toString())) {
                    ArrayList<String> otherPatternEdges = new ArrayList<>();
                    otherPattern.getGraph().edgeSet().forEach((edge) -> {otherPatternEdges.add(edge.toString());});
                    if (newPatternEdges.containsAll(otherPatternEdges)) {
                        System.out.println("Candidate pattern: " + newPattern);
						System.out.println("is a superset of pruned subgraph pattern: " + otherPattern.getPattern());
						isSupergraph = true;
					}
				}
			}
			i--;
		}
		printWithTime("isSuperGraphOfPrunedPattern", System.currentTimeMillis()-isSuperGraphOfPrunedPatternTime);
		return isSupergraph;
	}

	private PatternVertex isDuplicateVertex(VF2PatternGraph newPattern, String vertexType) {
		for (Vertex v: newPattern.getPattern().vertexSet()) {
			if (v.getTypes().contains(vertexType)) {
				return (PatternVertex) v;
			}
		}
		return null;
	}

	public ArrayList<ArrayList<DataVertex>> extractListOfCenterVertices(List<GraphLoader> graphs, String patternVertexType) {
		System.out.println("Extracting list of center vertices for vertex type "+patternVertexType);
		ArrayList<ArrayList<DataVertex>> matchesOfThisCenterVertex = new ArrayList<>(graphs.size());
		for (GraphLoader graph : graphs) {
			ArrayList<DataVertex> matchesInThisTimestamp = new ArrayList<>();
			for (Vertex vertex : graph.getGraph().getGraph().vertexSet()) {
				if (vertex.getTypes().contains(patternVertexType)) {
					DataVertex dataVertex = (DataVertex) vertex;
					if (this.isOnlyInterestingTGFDs()) {
						// Check if vertex contains at least one active attribute
						boolean containsActiveAttributes = false;
						for (String attrName : vertex.getAllAttributesNames()) {
							if (this.activeAttributesSet.contains(attrName)) {
								containsActiveAttributes = true;
								break;
							}
						}
						if (containsActiveAttributes) {
							matchesInThisTimestamp.add(dataVertex);
						}
					} else {
						matchesInThisTimestamp.add(dataVertex);
					}
				}
			}
			System.out.println("Number of matches found: " + matchesInThisTimestamp.size());
			matchesOfThisCenterVertex.add(matchesInThisTimestamp);
		}
		return matchesOfThisCenterVertex;
	}

	public int extractMatches(Set<RelationshipEdge> edgeSet, ArrayList<HashSet<ConstantLiteral>> matches, PatternTreeNode patternTreeNode, Map<String, List<Integer>> entityURIs, int timestamp) {
		String patternEdgeLabel = patternTreeNode.getGraph().edgeSet().iterator().next().getLabel();
		String sourceVertexType = patternTreeNode.getGraph().edgeSet().iterator().next().getSource().getTypes().iterator().next();
		String targetVertexType = patternTreeNode.getGraph().edgeSet().iterator().next().getTarget().getTypes().iterator().next();
		int numOfMatches = 0;
		for (RelationshipEdge edge: edgeSet) {
			String matchedEdgeLabel = edge.getLabel();
			Set<String> matchedSourceVertexType = edge.getSource().getTypes();
			Set<String> matchedTargetVertexType = edge.getTarget().getTypes();
			if (matchedEdgeLabel.equals(patternEdgeLabel) && matchedSourceVertexType.contains(sourceVertexType) && matchedTargetVertexType.contains(targetVertexType)) {
				numOfMatches++;
				HashSet<ConstantLiteral> literalsInMatch = new HashSet<>();
				Map<String, Integer> interestingnessMap = new HashMap<>();
				String entityURI = extractMatch(edge.getSource(), sourceVertexType, edge.getTarget(), targetVertexType, patternTreeNode, literalsInMatch, interestingnessMap);
				if (this.isOnlyInterestingTGFDs() && interestingnessMap.values().stream().anyMatch(n -> n < 2)) {
					continue;
				} else if (!this.isOnlyInterestingTGFDs() && literalsInMatch.size() <= patternTreeNode.getGraph().vertexSet().size()) {
					continue;
				}
				if (entityURI != null) {
					entityURIs.putIfAbsent(entityURI, new ArrayList<>(Arrays.asList(0, 0, 0)));
					entityURIs.get(entityURI).set(timestamp, entityURIs.get(entityURI).get(timestamp)+1);
				}
				matches.add(literalsInMatch);
			}
		}
		matches.sort(new Comparator<HashSet<ConstantLiteral>>() {
			@Override
			public int compare(HashSet<ConstantLiteral> o1, HashSet<ConstantLiteral> o2) {
				return o1.size() - o2.size();
			}
		});
		return numOfMatches;
	}

	public List<Set<Set<ConstantLiteral>>> getMatchesForPattern(List<GraphLoader> graphs, PatternTreeNode patternTreeNode) {
		// TO-DO: Potential speed up for single-edge/single-node patterns. Iterate through all edges/nodes in graph.
		Map<String, List<Integer>> entityURIs = new HashMap<>();
		List<Set<Set<ConstantLiteral>>> matchesPerTimestamps = new ArrayList<>();
		for (int timestamp = 0; timestamp < this.getNumOfSnapshots(); timestamp++) {
			matchesPerTimestamps.add(new HashSet<>());
		}

		patternTreeNode.getPattern().getCenterVertexType();

		for (int year = 0; year < this.getNumOfSnapshots(); year++) {
			long searchStartTime = System.currentTimeMillis();
			ArrayList<HashSet<ConstantLiteral>> matches = new ArrayList<>();
			int numOfMatchesInTimestamp = 0;
//			if (this.getCurrentVSpawnLevel() == 1) {
//				numOfMatchesInTimestamp = extractMatches(graphs.get(year).getGraph().getGraph().edgeSet(), matches, patternTreeNode, entityURIs);
//			} else {
				VF2AbstractIsomorphismInspector<Vertex, RelationshipEdge> results = new VF2SubgraphIsomorphism().execute2(graphs.get(year).getGraph(), patternTreeNode.getPattern(), false);
				if (results.isomorphismExists()) {
					numOfMatchesInTimestamp = extractMatches(results.getMappings(), matches, patternTreeNode, entityURIs, year);
				}
//			}
			System.out.println("Number of matches found: " + numOfMatchesInTimestamp);
			System.out.println("Number of matches found that contain active attributes: " + matches.size());
			matchesPerTimestamps.get(year).addAll(matches);
			printWithTime("Search Cost", (System.currentTimeMillis() - searchStartTime));
		}

		// TO-DO: Should we implement pattern support here to weed out patterns with few matches in later iterations?
		// Is there an ideal pattern support threshold after which very few TGFDs are discovered?
		// How much does the real pattern differ from the estimate?
		int numberOfMatchesFound = 0;
		for (Set<Set<ConstantLiteral>> matchesInOneTimestamp : matchesPerTimestamps) {
			numberOfMatchesFound += matchesInOneTimestamp.size();
		}
		System.out.println("Total number of matches found across all snapshots:" + numberOfMatchesFound);

		this.setPatternSupport(entityURIs, patternTreeNode);

		return matchesPerTimestamps;
	}

	// TO-DO: Merge with other extractMatch method?
	private String extractMatch(GraphMapping<Vertex, RelationshipEdge> result, PatternTreeNode patternTreeNode, HashSet<ConstantLiteral> match, Map<String, Integer> interestingnessMap) {
		String entityURI = null;
		for (Vertex v : patternTreeNode.getGraph().vertexSet()) {
			Vertex currentMatchedVertex = result.getVertexCorrespondence(v, false);
			if (currentMatchedVertex == null) continue;
			String patternVertexType = v.getTypes().iterator().next();
			if (entityURI == null) {
				entityURI = extractAttributes(patternTreeNode, patternVertexType, match, currentMatchedVertex, interestingnessMap);
			} else {
				extractAttributes(patternTreeNode, patternVertexType, match, currentMatchedVertex, interestingnessMap);
			}
		}
		return entityURI;
	}

	// TO-DO: Merge with other extractMatch method?
	private String extractMatch(Vertex currentSourceVertex, String sourceVertexType, Vertex currentTargetVertex, String targetVertexType, PatternTreeNode patternTreeNode, HashSet<ConstantLiteral> match, Map<String, Integer> interestingnessMap) {
		String entityURI = null;
		List<String> patternVerticesTypes = Arrays.asList(sourceVertexType, targetVertexType);
		List<Vertex> vertices = Arrays.asList(currentSourceVertex, currentTargetVertex);
		for (int index = 0; index < vertices.size(); index++) {
			Vertex currentMatchedVertex = vertices.get(index);
			String patternVertexType = patternVerticesTypes.get(index);
			if (entityURI == null) {
				entityURI = extractAttributes(patternTreeNode, patternVertexType, match, currentMatchedVertex, interestingnessMap);
			} else {
				extractAttributes(patternTreeNode, patternVertexType, match, currentMatchedVertex, interestingnessMap);
			}
		}
		return entityURI;
	}

	private String extractAttributes(PatternTreeNode patternTreeNode, String patternVertexType, HashSet<ConstantLiteral> match, Vertex currentMatchedVertex, Map<String, Integer> interestingnessMap) {
		String entityURI = null;
		String centerVertexType = patternTreeNode.getPattern().getCenterVertexType();
		Set<String> matchedVertexTypes = currentMatchedVertex.getTypes();
		for (ConstantLiteral activeAttribute : getActiveAttributesInPattern(patternTreeNode.getGraph().vertexSet(),true)) {
			if (!matchedVertexTypes.contains(activeAttribute.getVertexType())) continue;
			for (String matchedAttrName : currentMatchedVertex.getAllAttributesNames()) {
				if (matchedVertexTypes.contains(centerVertexType) && matchedAttrName.equals("uri")) {
					entityURI = currentMatchedVertex.getAttributeValueByName(matchedAttrName);
				}
				if (!activeAttribute.getAttrName().equals(matchedAttrName)) continue;
				String matchedAttrValue = currentMatchedVertex.getAttributeValueByName(matchedAttrName);
				ConstantLiteral xLiteral = new ConstantLiteral(patternVertexType, matchedAttrName, matchedAttrValue);
				interestingnessMap.merge(patternVertexType, 1, Integer::sum);
				match.add(xLiteral);
			}
		}
		return entityURI;
	}

	private int extractMatches(Iterator<GraphMapping<Vertex, RelationshipEdge>> iterator, ArrayList<HashSet<ConstantLiteral>> matches, PatternTreeNode patternTreeNode, Map<String, List<Integer>> entityURIs, int timestamp) {
		int numOfMatches = 0;
		while (iterator.hasNext()) {
			numOfMatches++;
			GraphMapping<Vertex, RelationshipEdge> result = iterator.next();
			HashSet<ConstantLiteral> literalsInMatch = new HashSet<>();
			Map<String, Integer> interestingnessMap = new HashMap<>();
			String entityURI = extractMatch(result, patternTreeNode, literalsInMatch, interestingnessMap);
			// ensures that the match is not empty and contains more than just the uri attribute
			if (this.isOnlyInterestingTGFDs() && interestingnessMap.values().stream().anyMatch(n -> n < 2)) {
				continue;
			} else if (!this.isOnlyInterestingTGFDs() && literalsInMatch.size() <= patternTreeNode.getGraph().vertexSet().size()) {
				continue;
			}
			if (entityURI != null) {
				entityURIs.putIfAbsent(entityURI, new ArrayList<>(Arrays.asList(0, 0, 0)));
				entityURIs.get(entityURI).set(timestamp, entityURIs.get(entityURI).get(timestamp)+1);
			}
			matches.add(literalsInMatch);
		}
		matches.sort(new Comparator<HashSet<ConstantLiteral>>() {
			@Override
			public int compare(HashSet<ConstantLiteral> o1, HashSet<ConstantLiteral> o2) {
				return o1.size() - o2.size();
			}
		});
		return numOfMatches;
	}

	public List<Set<Set<ConstantLiteral>>> getMatchesUsingChangeFiles(PatternTreeNode patternTreeNode) {
		// TO-DO: Should we use changefiles based on freq types??

		List<Set<Set<ConstantLiteral>>> matchesPerTimestamps = new ArrayList<>();
		for (int timestamp = 0; timestamp < this.getNumOfSnapshots(); timestamp++) {
			matchesPerTimestamps.add(new HashSet<>());
		}

//		patternTreeNode.getPattern().setDiameter(this.getCurrentVSpawnLevel());

		TGFD dummyTgfd = new TGFD();
		dummyTgfd.setName(patternTreeNode.getAllEdgeStrings().toString());
		dummyTgfd.setPattern(patternTreeNode.getPattern());

		System.out.println("-----------Snapshot (1)-----------");
		long startTime=System.currentTimeMillis();
		List<TGFD> tgfds = Collections.singletonList(dummyTgfd);
		int numberOfMatchesFound = 0;

		GraphLoader graph;
		if (this.getFirstSnapshotTypeModel() == null && this.getFirstSnapshotDataModel() == null) {
			for (String path : this.getTimestampToFilesMap().get(0).getValue()) {
				if (!path.toLowerCase().endsWith(".ttl") && !path.toLowerCase().endsWith(".nt"))
					continue;
				if (path.toLowerCase().contains("literals") || path.toLowerCase().contains("objects"))
					continue;
				Path input= Paths.get(path);
				Model model = ModelFactory.createDefaultModel();
				System.out.println("Reading Node Types: " + path);
				model.read(input.toUri().toString());
				this.setFirstSnapshotTypeModel(model);
			}
			Model dataModel = ModelFactory.createDefaultModel();
			for (String path: this.getTimestampToFilesMap().get(0).getValue()) {
				if (!path.toLowerCase().endsWith(".ttl") && !path.toLowerCase().endsWith(".nt"))
					continue;
				if (path.toLowerCase().contains("types"))
					continue;
				Path input= Paths.get(path);
				System.out.println("Reading data graph: "+path);
				dataModel.read(input.toUri().toString());
				this.setFirstSnapshotDataModel(dataModel);
			}
		}
//		Config.optimizedLoadingBasedOnTGFD = true; // TO-DO: Does enabling optimized loading cause problems with TypeChange?
		assert this.getFirstSnapshotTypeModel() != null;
		assert this.getFirstSnapshotDataModel() != null;
		if (this.getLoader().equals("dbpedia")) {
			graph = new DBPediaLoader(tgfds, Collections.singletonList(this.getFirstSnapshotTypeModel()), Collections.singletonList(this.getFirstSnapshotDataModel()));
		} else {
			graph = new IMDBLoader(tgfds, Collections.singletonList(this.getFirstSnapshotDataModel()));
		}
//		Config.optimizedLoadingBasedOnTGFD = false;

		if (this.isDissolveSuperVerticesBasedOnCount())
			this.dissolveSuperVerticesBasedOnCount(graph);

		printWithTime("Load graph (1)", System.currentTimeMillis()-startTime);

		// Now, we need to find the matches for each snapshot.
		// Finding the matches...
		Map<String, List<Integer>> entityURIs = new HashMap<>();

		for (TGFD tgfd : tgfds) {
			System.out.println("\n###########" + tgfd.getName() + "###########");

			//Retrieving and storing the matches of each timestamp.
			final long searchStartTime = System.currentTimeMillis();
			this.extractMatchesAcrossSnapshots(Collections.singletonList(graph), patternTreeNode, matchesPerTimestamps, entityURIs);
			printWithTime("Search Cost", (System.currentTimeMillis() - searchStartTime));
			numberOfMatchesFound += matchesPerTimestamps.get(0).size();
		}

		//Load the change files
		for (int i = 0; i < this.getNumOfSnapshots()-1; i++) {
			System.out.println("-----------Snapshot (" + (i+2) + ")-----------");

			startTime = System.currentTimeMillis();
			JSONArray changesJsonArray;
			if (this.isUseTypeChangeFile()) {
				System.out.println("Using type changefiles...");
				changesJsonArray = new JSONArray();
				for (RelationshipEdge e: patternTreeNode.getGraph().edgeSet()) {
					for (String type: e.getSource().getTypes()) {
						String changeFilePath = "changes_t" + (i + 1) + "_t" + (i + 2) + "_" + type + ".json";
						System.out.println(changeFilePath);
						JSONArray changesJsonArrayForType;
						if (this.isStoreInMemory()) {
							System.out.println("Getting changefile from memory");
							changesJsonArrayForType = this.getChangeFilesMap().get(changeFilePath);
						} else {
							System.out.println("Reading changefile from disk");
							changesJsonArrayForType = readJsonArrayFromFile(changeFilePath);
						}
						changesJsonArray.addAll(changesJsonArrayForType);
					}
				}
			} else {
				String changeFilePath = "changes_t" + (i + 1) + "_t" + (i + 2) + "_" + this.getGraphSize() + ".json";
				if (this.isStoreInMemory()) {
					changesJsonArray = this.getChangeFilesMap().get(changeFilePath);
				} else {
					changesJsonArray = readJsonArrayFromFile(changeFilePath);
				}
			}
			ChangeLoader changeLoader = new ChangeLoader(changesJsonArray);
			HashMap<Integer,HashSet<Change>> newChanges = changeLoader.getAllGroupedChanges();
			List<Entry<Integer,HashSet<Change>>> sortedChanges = new ArrayList<>(newChanges.entrySet());
			HashMap<ChangeType, Integer> map = new HashMap<>();
			map.put(ChangeType.deleteAttr, 1);
			map.put(ChangeType.insertAttr, 3);
			map.put(ChangeType.changeAttr, 3);
			map.put(ChangeType.deleteEdge, 0);
			map.put(ChangeType.insertEdge, 4);
			map.put(ChangeType.changeType, 2);
			map.put(ChangeType.deleteVertex, 0);
			map.put(ChangeType.insertVertex, 6);
			sortedChanges.sort(new Comparator<Entry<Integer, HashSet<Change>>>() {
				@Override
				public int compare(Entry<Integer, HashSet<Change>> o1, Entry<Integer, HashSet<Change>> o2) {
					if ((o1.getValue().iterator().next() instanceof AttributeChange || o1.getValue().iterator().next() instanceof TypeChange)
							&& (o2.getValue().iterator().next() instanceof AttributeChange || o2.getValue().iterator().next() instanceof TypeChange)) {
						boolean o1containsTypeChange = false;
						for (Change c: o1.getValue()) {
							if (c instanceof TypeChange) {
								o1containsTypeChange = true;
								break;
							}
						}
						boolean o2containsTypeChange = false;
						for (Change c: o2.getValue()) {
							if (c instanceof TypeChange) {
								o2containsTypeChange = true;
								break;
							}
						}
						if (o1containsTypeChange && !o2containsTypeChange) {
							return -1;
						} else if (!o1containsTypeChange && o2containsTypeChange) {
							return 1;
						} else {
							return 0;
						}
					}
					return map.get(o1.getValue().iterator().next().getTypeOfChange()).compareTo(map.get(o2.getValue().iterator().next().getTypeOfChange()));
				}
			});
			printWithTime("Load changes for Snapshot (" + (i+2) + ")", System.currentTimeMillis()-startTime);
			System.out.println("Total number of changes in changefile: " + newChanges.size());
			List<List<Entry<Integer,HashSet<Change>>>> changes = new ArrayList<>();
			changes.add(sortedChanges);

			System.out.println("Total number of changes: " + changes.size());

			// Now, we need to find the matches for each snapshot.
			// Finding the matches...

			startTime=System.currentTimeMillis();
			System.out.println("Updating the graph...");
			// TO-DO: Do we need to update the subgraphWithinDiameter method used in IncUpdates?
			IncUpdates incUpdatesOnDBpedia = new IncUpdates(graph.getGraph(), tgfds);
			incUpdatesOnDBpedia.AddNewVertices(changeLoader.getAllChanges());
			System.out.println("Added new vertices.");

			HashMap<String, TGFD> tgfdsByName = new HashMap<>();
			for (TGFD tgfd : tgfds) {
				tgfdsByName.put(tgfd.getName(), tgfd);
			}
			Map<String, Set<ConstantLiteral>> newMatches = new HashMap<>();
			Map<String, Set<ConstantLiteral>> removedMatches = new HashMap<>();
			int numOfNewMatchesFoundInSnapshot = 0;
			for (List<Entry<Integer, HashSet<Change>>> changesByFile: changes) {
				for (Entry<Integer, HashSet<Change>> entry : changesByFile) {
					HashMap<String, IncrementalChange> incrementalChangeHashMap = incUpdatesOnDBpedia.updateGraphByGroupOfChanges(entry.getValue(), tgfdsByName);
					if (incrementalChangeHashMap == null)
						continue;
					for (String tgfdName : incrementalChangeHashMap.keySet()) {
						for (Entry<String, Set<ConstantLiteral>> allLiteralsInNewMatchEntry : incrementalChangeHashMap.get(tgfdName).getNewMatches().entrySet()) {
							numOfNewMatchesFoundInSnapshot++;
							HashSet<ConstantLiteral> match = new HashSet<>();
							Map<String, Integer> interestingnessMap = new HashMap<>();
//							String entityURI = null;
							for (ConstantLiteral matchedLiteral: allLiteralsInNewMatchEntry.getValue()) {
								for (ConstantLiteral activeAttribute : getActiveAttributesInPattern(patternTreeNode.getGraph().vertexSet(), true)) {
									if (!matchedLiteral.getVertexType().equals(activeAttribute.getVertexType())) continue;
									if (!matchedLiteral.getAttrName().equals(activeAttribute.getAttrName())) continue;
//									if (matchedLiteral.getVertexType().equals(patternTreeNode.getPattern().getCenterVertexType()) && matchedLiteral.getAttrName().equals("uri")) {
//										entityURI = matchedLiteral.getAttrValue();
//									}
									ConstantLiteral xLiteral = new ConstantLiteral(matchedLiteral.getVertexType(), matchedLiteral.getAttrName(), matchedLiteral.getAttrValue());
									interestingnessMap.merge(matchedLiteral.getVertexType(), 1, Integer::sum);
									match.add(xLiteral);
								}
							}
							if (this.isOnlyInterestingTGFDs() && interestingnessMap.values().stream().anyMatch(n -> n < 2)) {
								continue;
							} else if (!this.isOnlyInterestingTGFDs() && match.size() <= patternTreeNode.getGraph().vertexSet().size()) {
								continue;
							}
							newMatches.put(allLiteralsInNewMatchEntry.getKey(), match);
						}

						for (Entry<String, Set<ConstantLiteral>> allLiteralsInRemovedMatchesEntry : incrementalChangeHashMap.get(tgfdName).getRemovedMatches().entrySet()) {
							HashSet<ConstantLiteral> match = new HashSet<>();
							Map<String, Integer> interestingnessMap = new HashMap<>();
//							String entityURI = null;
							for (ConstantLiteral matchedLiteral: allLiteralsInRemovedMatchesEntry.getValue()) {
								for (ConstantLiteral activeAttribute : getActiveAttributesInPattern(patternTreeNode.getGraph().vertexSet(), true)) {
									if (!matchedLiteral.getVertexType().equals(activeAttribute.getVertexType())) continue;
									if (!matchedLiteral.getAttrName().equals(activeAttribute.getAttrName())) continue;
//									if (matchedLiteral.getVertexType().equals(patternTreeNode.getPattern().getCenterVertexType()) && matchedLiteral.getAttrName().equals("uri")) {
//										entityURI = matchedLiteral.getAttrValue();
//									}
									ConstantLiteral xLiteral = new ConstantLiteral(matchedLiteral.getVertexType(), matchedLiteral.getAttrName(), matchedLiteral.getAttrValue());
									interestingnessMap.merge(matchedLiteral.getVertexType(), 1, Integer::sum);
									match.add(xLiteral);
								}
							}
							if (this.isOnlyInterestingTGFDs() && interestingnessMap.values().stream().anyMatch(n -> n < 2)) {
								continue;
							} else if (!this.isOnlyInterestingTGFDs() && match.size() <= patternTreeNode.getGraph().vertexSet().size()) {
								continue;
							}
							removedMatches.putIfAbsent(allLiteralsInRemovedMatchesEntry.getKey(), match);
						}
					}
				}
			}
			System.out.println("Number of new matches found: " + numOfNewMatchesFoundInSnapshot);
			System.out.println("Number of new matches found that contain active attributes: " + newMatches.size());
			System.out.println("Number of removed matched: " + removedMatches.size());

			for (Set<ConstantLiteral> newMatch: newMatches.values()) {
				for (ConstantLiteral l: newMatch) {
					if (l.getVertexType().equals(patternTreeNode.getPattern().getCenterVertexType())
							&& l.getAttrName().equals("uri")) {
						String entityURI = l.getAttrValue();
						entityURIs.putIfAbsent(entityURI, new ArrayList<>(Arrays.asList(0, 0, 0)));
						entityURIs.get(entityURI).set(i+1, entityURIs.get(entityURI).get(i+1)+1);
					}
				}
				matchesPerTimestamps.get(i+1).add(newMatch);
			}

			int numOfOldMatchesFoundInSnapshot = 0;
			for (Set<ConstantLiteral> previousMatch : matchesPerTimestamps.get(i)) {
				String centerVertexType = patternTreeNode.getPattern().getCenterVertexType();
				String entityURI = null;
				for (ConstantLiteral l: previousMatch) {
					if (l.getVertexType().equals(centerVertexType) && l.getAttrName().equals("uri")) {
						entityURI = l.getAttrValue();
						break;
					}
				}
				if (removedMatches.containsValue(previousMatch))
					continue;
				if (newMatches.containsValue(previousMatch))
					continue;
				if (entityURI != null) {
					entityURIs.putIfAbsent(entityURI, new ArrayList<>(Arrays.asList(0, 0, 0)));
					entityURIs.get(entityURI).set(i + 1, entityURIs.get(entityURI).get(i + 1) + 1);
				}
				matchesPerTimestamps.get(i+1).add(previousMatch);
				numOfOldMatchesFoundInSnapshot++;
			}
			System.out.println("Number of valid old matches that are not new or removed: " + numOfOldMatchesFoundInSnapshot);
			System.out.println("Total number of matches with active attributes found in this snapshot: " + matchesPerTimestamps.get(i+1).size());

			numberOfMatchesFound += matchesPerTimestamps.get(i+1).size();

			incUpdatesOnDBpedia.deleteVertices(changeLoader.getAllChanges());

			printWithTime("Update and retrieve matches", System.currentTimeMillis()-startTime);
		}

		System.out.println("-------------------------------------");
		System.out.println("Number of entity URIs found: "+entityURIs.size());
//		for (Map.Entry<String, List<Integer>> entry: entityURIs.entrySet()) {
//			System.out.println(entry);
//		}
		System.out.println("Total number of matches found in all snapshots: " + numberOfMatchesFound);
		this.setPatternSupport(entityURIs, patternTreeNode);

		return matchesPerTimestamps;
	}

	public static void printWithTime(String message, long runTimeInMS)
	{
		System.out.println(message + " time: " + runTimeInMS + "(ms) ** " +
				TimeUnit.MILLISECONDS.toSeconds(runTimeInMS) + "(sec) ** " +
				TimeUnit.MILLISECONDS.toMinutes(runTimeInMS) +  "(min)");
	}
}

