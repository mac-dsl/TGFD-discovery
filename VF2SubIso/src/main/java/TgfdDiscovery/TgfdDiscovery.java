package TgfdDiscovery;

import VF2Runner.VF2SubgraphIsomorphism;
import graphLoader.DBPediaLoader;
import infra.*;
import org.apache.commons.cli.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.GraphMapping;
import org.jgrapht.alg.isomorphism.VF2AbstractIsomorphismInspector;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;

public class TgfdDiscovery {
	private final int numOfSnapshots;
	public static final double DEFAULT_PATTERN_SUPPORT_THRESHOLD = 0.001;
	public static final int DEFAULT_GAMMA = 20;
	public static final int DEFAULT_K = 3;
	public static final double DEFAULT_THETA = 0.7;
	private Integer NUM_OF_EDGES_IN_GRAPH;
	public int NUM_OF_VERTICES_IN_GRAPH;
	public Map<String, Integer> uniqueVertexTypesHist; // freq nodes come from here
	public Map<String, HashSet<String>> vertexTypesAttributes; // freq attributes come from here
	public Map<String, Integer> uniqueEdgesHist; // freq edges come from here
	public PatternTree genTree;
	public boolean isNaive;
	public Long fileSuffix = null;
	private final boolean interestingTGFDs;
	private final int k;
	private final double theta;
	private final int gamma;
	private final double patternSupportThreshold;
	private HashSet<String> activeAttributesSet;
	private int previousLevelNodeIndex = 0;
	private int candidateEdgeIndex = 0;
	private int currentVSpawnLevel = 1;
	private final ArrayList<ArrayList<TGFD>> tgfds;

	public TgfdDiscovery(int numOfSnapshots) {
		this.k = DEFAULT_K;
		this.theta = DEFAULT_THETA;
		this.gamma = DEFAULT_GAMMA;
		this.numOfSnapshots = numOfSnapshots;
		this.patternSupportThreshold = DEFAULT_PATTERN_SUPPORT_THRESHOLD;
		this.isNaive = false;
		this.interestingTGFDs = true;
		this.tgfds = new ArrayList<>();
		for (int vSpawnLevel = 0; vSpawnLevel <= k; vSpawnLevel++) {
			tgfds.add(new ArrayList<>());
		}
		histogram();
		printHistogram();
		vSpawnInit();
		this.genTree.addLevel();
	}

	public TgfdDiscovery(int k, double theta, int gamma, double patternSupport, int numOfSnapshots, boolean isNaive, boolean interestingTGFDsOnly) {
		this.k = k;
		this.theta = theta;
		this.gamma = gamma;
		this.numOfSnapshots = numOfSnapshots;
		this.patternSupportThreshold = patternSupport;
		this.isNaive = isNaive;
		this.interestingTGFDs = interestingTGFDsOnly;
		this.tgfds = new ArrayList<>();
		for (int vSpawnLevel = 0; vSpawnLevel <= k; vSpawnLevel++) {
			tgfds.add(new ArrayList<>());
		}
		histogram();
		printHistogram();
		vSpawnInit();
		this.genTree.addLevel();
	}
	
	public void printTgfdsToFile(String experimentName, ArrayList<TGFD> tgfds) {
		tgfds.sort(new Comparator<TGFD>() {
			@Override
			public int compare(TGFD o1, TGFD o2) {
				return o2.getSupport().compareTo(o1.getSupport());
			}
		});
		System.out.println("Printing TGFDs to file for k = " + this.currentVSpawnLevel);
		try {
			String timeAndDateStamp = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu.MM.dd.HH.mm.ss"));
			PrintStream printStream = new PrintStream(experimentName + "-tgfds-" + (this.isNaive ? "naive" : "optimized") + (this.interestingTGFDs ? "-interesting" : "") + "-" + this.currentVSpawnLevel + "-" + String.format("%.1f", this.theta) + "-a" + this.gamma + "-" + timeAndDateStamp + ".txt");
			printStream.println("k = " + k);
			printStream.println("# of TGFDs generated = " + tgfds.size());
			for (TGFD tgfd : tgfds) {
				printStream.println(tgfd);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		System.gc();
	}

	public void printExperimentRuntimestoFile(String experimentName, TreeMap<String, Long> runtimes) {
		try {
			String timeAndDateStamp = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu.MM.dd.HH.mm.ss"));
			PrintStream printStream = new PrintStream(experimentName + "-experiments-runtimes-" + (this.isNaive ? "naive" : "optimized") + (interestingTGFDs ? "-interesting" : "") + "-" + timeAndDateStamp + ".txt");
			for (String key : runtimes.keySet()) {
				printStream.print(experimentName + " = " + key);
				printStream.println(", execution time = " + (runtimes.get(key)));
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {

		Options options = new Options();
		options.addOption("console", false, "print to console");

		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = null;
		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			e.printStackTrace();
		}
		assert cmd != null;

		if (!cmd.hasOption("console")) {
			String timeAndDateStamp = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu.MM.dd.HH.mm.ss"));
			PrintStream logStream = null;
			try {
				logStream = new PrintStream("tgfd-discovery-log-" + timeAndDateStamp + ".txt");
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			System.setOut(logStream);
		}

		ArrayList<DBPediaLoader> graphs = loadDBpediaSnapshots("");
		TgfdDiscovery tgfdDiscovery = new TgfdDiscovery(2, 0.7,20,0.001, graphs.size(), false, true);
		while (tgfdDiscovery.currentVSpawnLevel <= tgfdDiscovery.k) {

			System.out.println("VSpawn level " + tgfdDiscovery.currentVSpawnLevel);
			System.out.println("Previous level node index " + tgfdDiscovery.previousLevelNodeIndex);
			System.out.println("Candidate edge index " + tgfdDiscovery.candidateEdgeIndex);

			PatternTreeNode patternTreeNode = null;
			while (patternTreeNode == null) {
				patternTreeNode = tgfdDiscovery.vSpawn();
			}
			ArrayList<ArrayList<HashSet<ConstantLiteral>>> matches = new ArrayList<>();
			for (int timestamp = 0; timestamp < tgfdDiscovery.numOfSnapshots; timestamp++) {
				matches.add(new ArrayList<>());
			}
			int numOfMatchesFound = tgfdDiscovery.getMatchesForPattern(graphs, patternTreeNode, matches); // this can be called repeatedly on many graphs
			if (numOfMatchesFound == 0) continue;
			ArrayList<TGFD> tgfds = tgfdDiscovery.hSpawn(patternTreeNode, matches);
			tgfdDiscovery.tgfds.get(tgfdDiscovery.currentVSpawnLevel).addAll(tgfds);
		}
//		for (int level = 1; level < tgfdDiscovery.tgfds.size(); level++) {
//			tgfdDiscovery.printTgfdsToFile("api-test", level, tgfdDiscovery.theta, tgfdDiscovery.tgfds.get(level));
//		}
	}

//	public static void main2(String[] args) {
//
//		Options options = new Options();
//		options.addOption("console", false, "print to console");
//		options.addOption("naive", false, "run naive version of algorithm");
//		options.addOption("interesting", false, "run algorithm and only consider interesting TGFDs");
//		options.addOption("G", false, "run experiment on graph sizes 400K-1.6M");
//		options.addOption("g", true, "run experiment on a specific graph size");
//		options.addOption("Theta", false, "run experiment using support thresholds 0.9 to 0.1");
//		options.addOption("theta", true, "run experiment using a specific support threshold");
//		options.addOption("K", false, "run experiment for k = 1 to 5");
//		options.addOption("k", true, "run experiment for k iterations");
//		options.addOption("A", "run experiment for active attribute set sizes from 10 to 50");
//		options.addOption("a", "run experiment for specified active attribute set size");
//
//		CommandLineParser parser = new DefaultParser();
//		CommandLine cmd = null;
//		try {
//			cmd = parser.parse(options, args);
//		} catch (ParseException e) {
//			e.printStackTrace();
//		}
//		assert cmd != null;
//
//		if (!cmd.hasOption("console")) {
//			String timeAndDateStamp = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu.MM.dd.HH.mm.ss"));
//			PrintStream logStream = null;
//			try {
//				logStream = new PrintStream("tgfd-discovery-log-" + timeAndDateStamp + ".txt");
//			} catch (FileNotFoundException e) {
//				e.printStackTrace();
//			}
//			System.setOut(logStream);
//		}
//
//		if (cmd.hasOption("naive")) {
//			TgfdDiscovery.TgfdDiscovery.isNaive = true;
//		}
//
//		if (cmd.hasOption("interesting")) {
//			TgfdDiscovery.TgfdDiscovery.interestingTGFDs = true;
//		}
//
//		// |G| experiment
//		if (cmd.hasOption("G")) {
//			String experimentName = "G";
//			TgfdDiscovery.TgfdDiscovery.isNaive = false;
//			long[] sizes = {400000, 800000, 1200000, 1600000};
//			TreeMap<String, Long> gRuntimes = new TreeMap<>();
//			for (long size : sizes) {
//				// Compute Statistics
//				TgfdDiscovery.TgfdDiscovery.fileSuffix = size;
//				TgfdDiscovery.TgfdDiscovery.SIZE_OF_ACTIVE_ATTR_SET = cmd.getOptionValue("a") == null ? DEFAULT_GAMMA : Integer.parseInt(cmd.getOptionValue("a"));
//				histogram();
//				printHistogram();
//				System.gc();
//				double theta = cmd.getOptionValue("theta") == null ? 0.1 : Double.parseDouble(cmd.getOptionValue("theta"));
//				int k = cmd.getOptionValue("k") == null ? DEFAULT_K : Integer.parseInt(cmd.getOptionValue("k"));
//				System.out.println("Running experiment for |" + experimentName + "| = " + size);
//				final long startTime = System.currentTimeMillis();
//				discover(k, theta, experimentName + size + "-experiment");
//				final long endTime = System.currentTimeMillis();
//				final long runTime = endTime - startTime;
//				System.out.println("Total execution time for |" + experimentName + "| = " + size + " : " + runTime);
//				gRuntimes.put(Long.toString(size), runTime);
//				System.gc();
//			}
//			printExperimentRuntimestoFile(experimentName, gRuntimes);
//			System.out.println();
//			System.out.println("Runtimes for varying |G|:");
//			for (String size : gRuntimes.keySet()) {
//				System.out.print("|" + experimentName + "| = " + size);
//				System.out.println(", execution time = " + (gRuntimes.get(size)));
//			}
//			System.out.println();
//		}
//
//		// theta-experiments
//		// Compute Statistics
//		if (cmd.hasOption("Theta")) {
//			if (cmd.getOptionValue("g") != null) {
//				TgfdDiscovery.TgfdDiscovery.fileSuffix = Long.parseLong(cmd.getOptionValue("g"));
//			}
//			TgfdDiscovery.TgfdDiscovery.SIZE_OF_ACTIVE_ATTR_SET = cmd.getOptionValue("a") == null ? DEFAULT_GAMMA : Integer.parseInt(cmd.getOptionValue("a"));
//			histogram();
//			printHistogram();
//			System.gc();
//			String experimentName = "theta";
//			System.out.println("Varying " + experimentName);
//			TreeMap<String, Long> thetaRuntimes = new TreeMap<>();
//			for (double theta = 0.9; theta > 0.0; theta -= 0.1) {
////				double theta = 0.5;
//				System.out.println("Running experiment for theta = " + String.format("%.1f", theta));
//				final long startTime = System.currentTimeMillis();
//				int k = cmd.getOptionValue("k") == null ? DEFAULT_K : Integer.parseInt(cmd.getOptionValue("k"));
//				discover(k, theta, experimentName + String.format("%.1f", theta) + "-experiment");
//				final long endTime = System.currentTimeMillis();
//				final long runTime = endTime - startTime;
//				System.out.println("Total execution time for theta = " + String.format("%.1f", theta) + " : " + runTime);
//				thetaRuntimes.put(String.format("%.1f", theta), runTime);
//				System.gc();
//			}
//			printExperimentRuntimestoFile(experimentName, thetaRuntimes);
//			System.out.println();
//			System.out.println("Runtimes for varying " + experimentName + ":");
//			for (String thetaValue : thetaRuntimes.keySet()) {
//				System.out.println(experimentName + " = " + thetaValue);
//				System.out.println("Total execution time: " + (thetaRuntimes.get(thetaValue)));
//			}
//			System.out.println();
//		}
//
//		// k-experiments
//		if (cmd.hasOption("K")) {
//			if (cmd.getOptionValue("g") != null) {
//				TgfdDiscovery.TgfdDiscovery.fileSuffix = Long.parseLong(cmd.getOptionValue("g"));
//			}
//
//			TgfdDiscovery.TgfdDiscovery.SIZE_OF_ACTIVE_ATTR_SET = cmd.getOptionValue("a") == null ? DEFAULT_GAMMA : Integer.parseInt(cmd.getOptionValue("a"));
//
//			// Compute Statistics
//			histogram();
//			printHistogram();
//			System.gc();
//
//			System.out.println("Varying k");
//			int k = cmd.getOptionValue("k") == null ? 5 : Integer.parseInt(cmd.getOptionValue("k"));
//			double theta = cmd.getOptionValue("theta") == null ? DEFAULT_THETA : Double.parseDouble(cmd.getOptionValue("theta"));
//			System.out.println("Running experiment for k = " + k);
//			discover(k, theta, "k" + k + "-experiment");
//		}
//
//		if (cmd.hasOption("A")) {
//			if (cmd.getOptionValue("g") != null) {
//				TgfdDiscovery.TgfdDiscovery.fileSuffix = Long.parseLong(cmd.getOptionValue("g"));
//			}
//			String experimentName = "gamma";
//			System.out.println("Varying " + experimentName);
//			TreeMap<String, Long> gammaRuntimes = new TreeMap<>();
//			for (int gamma = 10; gamma < 60; gamma+=10) {
//				TgfdDiscovery.TgfdDiscovery.SIZE_OF_ACTIVE_ATTR_SET = gamma;
//				// Compute Statistics
//				histogram();
//				printHistogram();
//				System.gc();
//
//				int k = cmd.getOptionValue("k") == null ? DEFAULT_K : Integer.parseInt(cmd.getOptionValue("k"));
//				double theta = cmd.getOptionValue("theta") == null ? DEFAULT_THETA : Double.parseDouble(cmd.getOptionValue("theta"));
//				System.out.println("Running experiment for "+experimentName+" = " + SIZE_OF_ACTIVE_ATTR_SET);
//
//				final long startTime = System.currentTimeMillis();
//				discover(k, theta, experimentName + gamma + "-experiment");
//				final long endTime = System.currentTimeMillis();
//				final long runTime = endTime - startTime;
//				System.out.println("Total execution time for "+experimentName+" = " + gamma + " : " + runTime);
//				gammaRuntimes.put(Integer.toString(gamma), runTime);
//				System.gc();
//			}
//			printExperimentRuntimestoFile(experimentName, gammaRuntimes);
//			System.out.println();
//			System.out.println("Runtimes for varying " + experimentName + ":");
//			for (String gammaValue : gammaRuntimes.keySet()) {
//				System.out.println(experimentName + " = " + gammaValue);
//				System.out.println("Total execution time: " + (gammaRuntimes.get(gammaValue)));
//			}
//			System.out.println();
//		}
//
//		// Custom experiment
//		if (!cmd.hasOption("K") && !cmd.hasOption("G") && !cmd.hasOption("Theta") && !cmd.hasOption("A")) {
//			if (cmd.getOptionValue("g") != null) {
//				TgfdDiscovery.TgfdDiscovery.fileSuffix = Long.parseLong(cmd.getOptionValue("g"));
//			}
//			String fileSuffix = TgfdDiscovery.TgfdDiscovery.fileSuffix == null ? "" : Long.toString(TgfdDiscovery.TgfdDiscovery.fileSuffix);
//			TgfdDiscovery.TgfdDiscovery.SIZE_OF_ACTIVE_ATTR_SET = cmd.getOptionValue("a") == null ? DEFAULT_GAMMA : Integer.parseInt(cmd.getOptionValue("a"));
//			// Compute Statistics
//			histogram();
//			printHistogram();
//			System.gc();
//			TreeMap<String, Long> runtimes = new TreeMap<>();
//			double theta = cmd.getOptionValue("theta") == null ? DEFAULT_THETA : Double.parseDouble(cmd.getOptionValue("theta"));
//			int k = cmd.getOptionValue("k") == null ? DEFAULT_K : Integer.parseInt(cmd.getOptionValue("k"));
//			System.out.println("Running experiment for |G| = " + fileSuffix + ", k = " + k + ", theta = " + String.format("%.1f", theta));
//			final long startTime = System.currentTimeMillis();
//			discover(k, theta, "G" + fileSuffix + "-k" + k + "-theta" + String.format("%.1f", theta) + "-experiment");
//			final long endTime = System.currentTimeMillis();
//			final long runTime = endTime - startTime;
//			System.out.println("Total execution time for |G| = " + fileSuffix + ", k = " + k + ", theta = " + String.format("%.1f", theta) + ", gamma = "+SIZE_OF_ACTIVE_ATTR_SET+" : " + runTime);
//			runtimes.put("|G| = " + fileSuffix + ", k = " + k + ", theta = " + String.format("%.1f", theta)+ ", gamma = "+SIZE_OF_ACTIVE_ATTR_SET, runTime);
//			printExperimentRuntimestoFile("custom", runtimes);
//		}
//	}

	public void computeNodeHistogram() {

		System.out.println("Computing Node Histogram");

		Map<String, Integer> vertexTypeHistogram = new HashMap<>();
//		Map<String, Map<String, Integer>> tempVertexAttrFreqMap = new HashMap<>();
		Map<String, Set<String>> tempVertexAttrFreqMap = new HashMap<>();
		Map<String, String> vertexNameToTypeMap = new HashMap<>();
		Model model = ModelFactory.createDefaultModel();

		for (int i = 5; i < 8; i++) {
			String fileSuffix = this.fileSuffix == null ? "" : "-" + this.fileSuffix;
			String fileName = "201" + i + "types" + fileSuffix + ".ttl";
			Path input = Paths.get(fileName);
			System.out.println("Reading " + fileName);
			model.read(input.toUri().toString());
			StmtIterator typeTriples = model.listStatements();
			while (typeTriples.hasNext()) {
				Statement stmt = typeTriples.nextStatement();
				String vertexType = stmt.getObject().asResource().getLocalName().toLowerCase();
				String vertexName = stmt.getSubject().getURI().toLowerCase();
				if (vertexName.length() > 28) {
					vertexName = vertexName.substring(28);
				}
				vertexTypeHistogram.merge(vertexType, 1, Integer::sum);
//			tempVertexAttrFreqMap.putIfAbsent(vertexType, new HashMap<String, Integer>());
				tempVertexAttrFreqMap.putIfAbsent(vertexType, new HashSet<String>());
				vertexNameToTypeMap.putIfAbsent(vertexName, vertexType); // Every vertex in DBpedia only has one type?
			}
		}

		this.NUM_OF_VERTICES_IN_GRAPH = 0;
		for (Map.Entry<String, Integer> entry : vertexTypeHistogram.entrySet()) {
			this.NUM_OF_VERTICES_IN_GRAPH += entry.getValue();
		}
		System.out.println("Number of vertices in graph: " + this.NUM_OF_VERTICES_IN_GRAPH);

		this.uniqueVertexTypesHist = vertexTypeHistogram;

		computeAttrHistogram(vertexNameToTypeMap, tempVertexAttrFreqMap);
		computeEdgeHistogram(vertexNameToTypeMap);

	}

	//	public static void computeAttrHistogram(Map<String, String> nodesRecord, Map<String, Map<String, Integer>> tempVertexAttrFreqMap) {
	public void computeAttrHistogram(Map<String, String> nodesRecord, Map<String, Set<String>> tempVertexAttrFreqMap) {
		System.out.println("Computing attributes histogram");

		Map<String, Integer> attrHistMap = new HashMap<>();
		Map<String, Set<String>> attrDistributionMap = new HashMap<>();

		Model model = ModelFactory.createDefaultModel();
		for (int i = 5; i < 8; i++) {
			String fileSuffix = this.fileSuffix == null ? "" : "-" + this.fileSuffix;
			String fileName = "201" + i + "literals" + fileSuffix + ".ttl";
			Path input = Paths.get(fileName);
			System.out.println("Reading " + fileName);
			model.read(input.toUri().toString());
		}
		StmtIterator typeTriples = model.listStatements();
		while (typeTriples.hasNext()) {
			Statement stmt = typeTriples.nextStatement();
			String vertexName = stmt.getSubject().getURI().toLowerCase();
			if (vertexName.length() > 28) {
				vertexName = vertexName.substring(28);
			}
			String attrName = stmt.getPredicate().getLocalName().toLowerCase();
			if (nodesRecord.get(vertexName) != null) {
				attrHistMap.merge(attrName, 1, Integer::sum);
				String vertexType = nodesRecord.get(vertexName);
				if (tempVertexAttrFreqMap.containsKey(vertexType)) {
//					tempVertexAttrFreqMap.get(vertexType).merge(attrName, 1, Integer::sum);
					tempVertexAttrFreqMap.get(vertexType).add(attrName);
				}
				if (!attrDistributionMap.containsKey(attrName)) {
					attrDistributionMap.put(attrName, new HashSet<>());
				} else {
					attrDistributionMap.get(attrName).add(vertexType);
				}
			}
		}

		ArrayList<Entry<String,Set<String>>> sortedAttrDistributionMap = new ArrayList<>(attrDistributionMap.entrySet());
		if (!this.isNaive) {
			sortedAttrDistributionMap.sort(new Comparator<Entry<String, Set<String>>>() {
				@Override
				public int compare(Entry<String, Set<String>> o1, Entry<String, Set<String>> o2) {
					return o2.getValue().size() - o1.getValue().size();
				}
			});
		}
		HashSet<String> mostDistributedAttributesSet = new HashSet<>();
		for (Entry<String, Set<String>> attrNameEntry : sortedAttrDistributionMap.subList(0, Math.min(this.gamma, sortedAttrDistributionMap.size()))) {
			mostDistributedAttributesSet.add(attrNameEntry.getKey());
		}
		this.activeAttributesSet = mostDistributedAttributesSet;

//		tgfdDiscovery.attrHist = attrHistMap;
		ArrayList<Entry<String, Integer>> sortedHistogram = new ArrayList<>(attrHistMap.entrySet());
		if (!this.isNaive) {
			sortedHistogram.sort(new Comparator<Entry<String, Integer>>() {
				@Override
				public int compare(Entry<String, Integer> o1, Entry<String, Integer> o2) {
					return o2.getValue() - o1.getValue();
				}
			});
		}

//		HashSet<String> mostFrequentAttributesSet = new HashSet<>();
//		for (Map.Entry<String, Integer> attrNameEntry : sortedHistogram.subList(0, Math.min(SIZE_OF_ACTIVE_ATTR_SET, sortedHistogram.size()))) {
//			mostFrequentAttributesSet.add(attrNameEntry.getKey());
//		}
//		tgfdDiscovery.activeAttributesSet = mostFrequentAttributesSet;

		Map<String, HashSet<String>> vertexTypesAttributes = new HashMap<>();
		for (String vertexType : tempVertexAttrFreqMap.keySet()) {
			Set<String> attrNameSet = tempVertexAttrFreqMap.get(vertexType);
			vertexTypesAttributes.put(vertexType, new HashSet<>());
			for (String attrName : attrNameSet) {
				if (mostDistributedAttributesSet.contains(attrName)) { // Filters non-frequent attributes
					vertexTypesAttributes.get(vertexType).add(attrName);
				}
			}
		}


		this.vertexTypesAttributes = vertexTypesAttributes;
	}

	public void computeEdgeHistogram(Map<String, String> nodesRecord) {
		System.out.println("Computing edges histogram");

		Map<String, Integer> edgesHist = new HashMap<>();

		Model model = ModelFactory.createDefaultModel();
		for (int i = 5; i < 8; i++) {
			String fileSuffix = this.fileSuffix == null ? "" : "-" + this.fileSuffix;
			String fileName = "201" + i + "objects" + fileSuffix + ".ttl";
			Path input = Paths.get(fileName);
			System.out.println("Reading " + fileName);
			model.read(input.toUri().toString());

			StmtIterator typeTriples = model.listStatements();
			while (typeTriples.hasNext()) {
				Statement stmt = typeTriples.nextStatement();
				String subjectName = stmt.getSubject().getURI().toLowerCase();
				if (subjectName.length() > 28) {
					subjectName = subjectName.substring(28);
				}
				String predicateName = stmt.getPredicate().getLocalName().toLowerCase();
				String objectName = stmt.getObject().toString().substring(stmt.getObject().toString().lastIndexOf("/") + 1).toLowerCase();
				if (nodesRecord.get(subjectName) != null && nodesRecord.get(objectName) != null) {
					String uniqueEdge = nodesRecord.get(subjectName) + " " + predicateName + " " + nodesRecord.get(objectName);
					edgesHist.merge(uniqueEdge, 1, Integer::sum);
				}
			}
		}

		this.NUM_OF_EDGES_IN_GRAPH = 0;
		for (Map.Entry<String, Integer> entry : edgesHist.entrySet()) {
			this.NUM_OF_EDGES_IN_GRAPH += entry.getValue();
		}
		System.out.println("Number of edges in graph: " + this.NUM_OF_EDGES_IN_GRAPH);

		this.uniqueEdgesHist = edgesHist;
	}

	public List<Entry<String, Integer>> getFrequentSortedVetexTypesHistogram() {
		ArrayList<Entry<String, Integer>> sortedVertexTypeHistogram = new ArrayList<>(this.uniqueVertexTypesHist.entrySet());
		sortedVertexTypeHistogram.sort(new Comparator<Entry<String, Integer>>() {
			@Override
			public int compare(Entry<String, Integer> o1, Entry<String, Integer> o2) {
				return o2.getValue() - o1.getValue();
			}
		});
		int size = 0;
		for (Entry<String, Integer> entry : sortedVertexTypeHistogram) {
			if (1.0 * entry.getValue() / NUM_OF_VERTICES_IN_GRAPH >= this.patternSupportThreshold) {
				size++;
			} else {
				break;
			}
		}
		if (this.isNaive) {
//			return (new ArrayList<>(uniqueVertexTypesHist.entrySet())).subList(0, size);
			return (new ArrayList<>(this.uniqueVertexTypesHist.entrySet()));
		}
		return sortedVertexTypeHistogram.subList(0, size);
	}

	public List<Entry<String, Integer>> getSortedEdgeHistogram() {
		ArrayList<Entry<String, Integer>> sortedEdgesHist = new ArrayList<>(this.uniqueEdgesHist.entrySet());
		sortedEdgesHist.sort(new Comparator<Entry<String, Integer>>() {
			@Override
			public int compare(Entry<String, Integer> o1, Entry<String, Integer> o2) {
				return o2.getValue() - o1.getValue();
			}
		});
		int size = 0;
		for (Entry<String, Integer> entry : sortedEdgesHist) {
			if (1.0 * entry.getValue() / NUM_OF_EDGES_IN_GRAPH >= this.patternSupportThreshold) {
				size++;
			} else {
				break;
			}
		}
		if (this.isNaive) {
//			return (new ArrayList<>(uniqueEdgesHist.entrySet())).subList(0, size);
			return (new ArrayList<>(uniqueEdgesHist.entrySet()));
		}
		return sortedEdgesHist.subList(0, size);
	}

	public void histogram() {
		computeNodeHistogram();
	}

	public void printHistogram() {
		List<Entry<String, Integer>> sortedHistogram = getFrequentSortedVetexTypesHistogram();
		List<Entry<String, Integer>> sortedEdgeHistogram = getSortedEdgeHistogram();

		System.out.println("Number of node types: " + sortedHistogram.size());
		System.out.println("Frequent Nodes:");
//		for (int i=0; i < 10; i++) {
		for (Entry<String, Integer> entry : sortedHistogram) {
			String vertexType = entry.getKey();
			Set<String> attributes = this.vertexTypesAttributes.get(vertexType);
//			List<Entry<String, Integer>> attributes = vertexTypesAttributes.get(vertexType);
//			ArrayList<Entry<String, Integer>> attributes = (ArrayList<Entry<String, Integer>>) nodesHist.get(i).getValue().get("attributes");
			System.out.println(vertexType + "={count=" + entry.getValue() + ", attributes=" + attributes + "}");
		}
//		System.out.println();
//		System.out.println("Number of attribute types: " + attrSortedHist.size());
//		System.out.println("Attributes:");
//		for (int i=0; i < 10; i++) {
//			System.out.println(attrSortedHist.get(i));
//		}
		System.out.println();
		System.out.println("Size of active attributes set: " + this.activeAttributesSet.size());
		System.out.println("Attributes:");
		for (String attrName : this.activeAttributesSet) {
			System.out.println(attrName);
		}
		System.out.println();
		System.out.println("Number of edge types: " + uniqueEdgesHist.size());
		System.out.println("Frequent Edges:");
//		for (int i=0; i < 10; i++) {
		for (Entry<String, Integer> entry : sortedEdgeHistogram) {
			System.out.println("edge=\"" + entry.getKey() + "\", count=" + entry.getValue());
		}
		System.out.println();
	}

	public static Map<Set<ConstantLiteral>, ArrayList<Entry<ConstantLiteral, List<Integer>>>> findMatches2(List<ConstantLiteral> attributes, ArrayList<ArrayList<HashSet<ConstantLiteral>>> matchesPerTimestamps) {
		String yVertexType = attributes.get(attributes.size()-1).getVertexType();
		String yAttrName = attributes.get(attributes.size()-1).getAttrName();
		List<ConstantLiteral> xAttributes = attributes.subList(0,attributes.size()-1);
		Map<Set<ConstantLiteral>, Map<ConstantLiteral, List<Integer>>> entitiesWithRHSvalues = new HashMap<>();
		int t = 2015;
		for (ArrayList<HashSet<ConstantLiteral>> matchesInOneTimeStamp : matchesPerTimestamps) {
			System.out.println("---------- Attribute values in " + t + " ---------- ");
			int numOfMatches = 0;
			if (matchesInOneTimeStamp.size() > 0) {
				for(HashSet<ConstantLiteral> match : matchesInOneTimeStamp) {
					Set<ConstantLiteral> entity = new HashSet<>();
					ConstantLiteral rhs = null;
					for (ConstantLiteral literalInMatch : match) {
						if (literalInMatch.getVertexType().equals(yVertexType) && literalInMatch.getAttrName().equals(yAttrName)) {
							rhs = new ConstantLiteral(literalInMatch.getVertexType(), literalInMatch.getAttrName(), literalInMatch.getAttrValue());
							continue;
						}
						for (ConstantLiteral attibute : xAttributes) {
							if (literalInMatch.getVertexType().equals(attibute.getVertexType()) && literalInMatch.getAttrName().equals(attibute.getAttrName())) {
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

	public HashSet<ConstantLiteral> getActiveAttributesInPattern(Set<Vertex> vertexSet) {
		HashMap<String, HashSet<String>> patternVerticesAttributes = new HashMap<>();
		for (Vertex vertex : vertexSet) {
			for (String vertexType : vertex.getTypes()) {
				patternVerticesAttributes.put(vertexType, new HashSet<>());
				Set<String> attrNameSet = this.vertexTypesAttributes.get(vertexType);
				for (String attrName : attrNameSet) {
					patternVerticesAttributes.get(vertexType).add(attrName);
				}
			}
		}
		HashSet<ConstantLiteral> literals = new HashSet<>();
		for (String vertexType : patternVerticesAttributes.keySet()) {
			for (String attrName : patternVerticesAttributes.get(vertexType)) {
				ConstantLiteral literal = new ConstantLiteral(vertexType, attrName, null);
				literals.add(literal);
			}
		}
		return literals;
	}

	public static boolean isPathVisited(ArrayList<ConstantLiteral> path, ArrayList<ArrayList<ConstantLiteral>> visitedPaths) {
		for (ArrayList<ConstantLiteral> visitedPath : visitedPaths) {
			if (visitedPath.size() == path.size()
					&& visitedPath.subList(0,visitedPath.size()-1).containsAll(path.subList(0, path.size()-1))
					&& visitedPath.get(visitedPath.size()-1).equals(path.get(path.size()-1))) {
				System.out.println("This dependency was already visited.");
				return true;
			}
		}
		return false;
	}

	public static boolean isZeroEntityPath(ArrayList<ConstantLiteral> path, PatternTreeNode patternTreeNode) {
		// Get all zero-entity paths in pattern tree
		ArrayList<ArrayList<ConstantLiteral>> zeroEntityPaths = patternTreeNode.getAllZeroEntityDependenciesOnThisPath();
		for (ArrayList<ConstantLiteral> zeroEntityPath : zeroEntityPaths) {
			if (path.get(path.size()-1).equals(zeroEntityPath.get(zeroEntityPath.size()-1)) && path.subList(0, path.size()-1).containsAll(zeroEntityPath.subList(0,zeroEntityPath.size()-1))) {
				System.out.println("Candidate dependency is a superset of zero-entity dependency: " + zeroEntityPath);
				return true;
			}
		}
		return false;
	}

	public static boolean isMinimalPath(ArrayList<ConstantLiteral> path, ArrayList<ArrayList<ConstantLiteral>> minimalPaths) {
		for (ArrayList<ConstantLiteral> minimalPath : minimalPaths) {
			if (path.get(path.size()-1).equals(minimalPath.get(minimalPath.size()-1)) && path.subList(0, path.size()-1).containsAll(minimalPath.subList(0,minimalPath.size()-1))) {
				System.out.println("Candidate dependency is a superset of minimal dependency: " + minimalPath);
				return true;
			}
		}
		return false;
	}

	public ArrayList<TGFD> deltaDiscovery(double theta, PatternTreeNode patternNode, LiteralTreeNode literalTreeNode, ArrayList<ConstantLiteral> literalPath, ArrayList<ArrayList<HashSet<ConstantLiteral>>> matchesPerTimestamps) {
		ArrayList<TGFD> tgfds = new ArrayList<>();

		// Add dependency attributes to pattern
		// TO-DO: Fix - when multiple vertices in a pattern have the same type, attribute values get overwritten
		VF2PatternGraph patternForDependency = patternNode.getPattern().copy();
		Set<ConstantLiteral> attributesSetForDependency = new HashSet<>(literalPath);
		for (Vertex v : patternForDependency.getPattern().vertexSet()) {
			String vType = new ArrayList<>(v.getTypes()).get(0);
			for (ConstantLiteral attribute : attributesSetForDependency) {
				if (vType.equals(attribute.getVertexType())) {
					v.addAttribute(new Attribute(attribute.getAttrName()));
				}
			}
		}

		System.out.println("Pattern: " + patternForDependency);
		System.out.println("Dependency: " + "\n\tY=" + literalPath.get(literalPath.size()-1) + ",\n\tX=" + literalPath.subList(0,literalPath.size()-1) + "\n\t}");

		System.out.println("Performing Entity Discovery");

		// Discover entities
		Map<Set<ConstantLiteral>, ArrayList<Entry<ConstantLiteral, List<Integer>>>> entities = findMatches2(literalPath, matchesPerTimestamps);
		if (entities == null) {
			System.out.println("Mark as Pruned. No matches found during entity discovery.");
			literalTreeNode.setIsPruned();
			patternNode.addZeroEntityDependency(literalPath);
			return tgfds;
		}

		System.out.println("Discovering constant TGFDs");

		// Find Constant TGFDs
		ArrayList<Pair> constantXdeltas = new ArrayList<>();
		ArrayList<TreeSet<Pair>> satisfyingAttrValues = new ArrayList<>();
		ArrayList<TGFD> constantTGFDs = discoverConstantTGFDs(theta, patternNode, literalPath.get(literalPath.size()-1), entities, constantXdeltas, satisfyingAttrValues);
		// TO-DO: Try discover general TGFD even if no constant TGFD candidate met support threshold
		System.out.println("Constant TGFDs discovered: " + constantTGFDs.size());
		tgfds.addAll(constantTGFDs);

		System.out.println("Discovering general TGFDs");

		// Find general TGFDs
		ArrayList<TGFD> generalTGFD = discoverGeneralTGFD(theta, patternForDependency, patternNode.getPatternSupport(), literalPath, entities.size(), constantXdeltas, satisfyingAttrValues);
		if (generalTGFD.size() > 0) {
			System.out.println("Marking literal node as pruned. Discovered general TGFDs for this dependency.");
			literalTreeNode.setIsPruned();
			patternNode.addMinimalDependency(literalPath);
		}
		tgfds.addAll(generalTGFD);

		return tgfds;
	}

	private ArrayList<TGFD> discoverGeneralTGFD(double theta, VF2PatternGraph patternForDependency, double patternSupport, ArrayList<ConstantLiteral> literalPath, int entitiesSize, ArrayList<Pair> constantXdeltas, ArrayList<TreeSet<Pair>> satisfyingAttrValues) {

		ArrayList<TGFD> tgfds = new ArrayList<>();

		System.out.println("Size of constantXdeltas: " + constantXdeltas.size());
		for (Pair deltaPair : constantXdeltas) {
			System.out.println("constant delta: " + deltaPair);
		}

		System.out.println("Size of satisfyingAttrValues: " + satisfyingAttrValues.size());
		for (Set<Pair> satisfyingPairs : satisfyingAttrValues) {
			System.out.println("satisfyingAttrValues entry: " + satisfyingPairs);
		}

		// Find intersection delta
		HashMap<Pair, ArrayList<TreeSet<Pair>>> intersections = new HashMap<>();
		int currMin = 0;
		int currMax = this.numOfSnapshots - 1;
		// TO-DO: Verify if TreeSet<Pair> is being sorted correctly
		ArrayList<TreeSet<Pair>> currSatisfyingAttrValues = new ArrayList<>();
		for (int index = 0; index < constantXdeltas.size(); index++) {
			Pair deltaPair = constantXdeltas.get(index);
			if (Math.max(currMin, deltaPair.min()) <= Math.min(currMax, deltaPair.max())) {
				currMin = Math.max(currMin, deltaPair.min());
				currMax = Math.min(currMax, deltaPair.max());
				currSatisfyingAttrValues.add(satisfyingAttrValues.get(index)); // By axiom 4
			} else {
				intersections.putIfAbsent(new Pair(currMin, currMax), currSatisfyingAttrValues);
				currSatisfyingAttrValues = new ArrayList<>();
				currMin = 0;
				currMax = this.numOfSnapshots - 1;
				if (Math.max(currMin, deltaPair.min()) <= Math.min(currMax, deltaPair.max())) {
					currMin = Math.max(currMin, deltaPair.min());
					currMax = Math.min(currMax, deltaPair.max());
					currSatisfyingAttrValues.add(satisfyingAttrValues.get(index));
				}
			}
		}
		intersections.putIfAbsent(new Pair(currMin, currMax), currSatisfyingAttrValues);

		ArrayList<Entry<Pair, ArrayList<TreeSet<Pair>>>> sortedIntersections = new ArrayList<>(intersections.entrySet());
		sortedIntersections.sort(new Comparator<Entry<Pair, ArrayList<TreeSet<Pair>>>>() {
			@Override
			public int compare(Entry<Pair, ArrayList<TreeSet<Pair>>> o1, Entry<Pair, ArrayList<TreeSet<Pair>>> o2) {
				return o2.getValue().size() - o1.getValue().size();
			}
		});

		System.out.println("Candidate deltas for general TGFD:");
		for (Entry<Pair, ArrayList<TreeSet<Pair>>> intersection : sortedIntersections) {
			System.out.println(intersection.getKey());
		}

		for (Entry<Pair, ArrayList<TreeSet<Pair>>> intersection : sortedIntersections) {
			int generalMin = intersection.getKey().min();
			int generalMax = intersection.getKey().max();
			System.out.println("General min: " + generalMin);
			System.out.println("General max: " + generalMax);

			// Compute general support
			float numerator = 0;
			float denominator = 2 * entitiesSize * this.numOfSnapshots;

			int numberOfSatisfyingPairs = 0;
			for (TreeSet<Pair> timestamps : intersection.getValue()) {
				TreeSet<Pair> satisfyingPairs = new TreeSet<Pair>();
				for (Pair timestamp : timestamps) {
					if (timestamp.max() - timestamp.min() >= generalMin && timestamp.max() - timestamp.min() <= generalMax) {
						satisfyingPairs.add(new Pair(timestamp.min(), timestamp.max()));
					}
				}
				numberOfSatisfyingPairs += satisfyingPairs.size();
			}

			System.out.println("Number of satisfying pairs: " + numberOfSatisfyingPairs);

			numerator = numberOfSatisfyingPairs;

			float support = numerator / denominator;
			if (support < theta) {
				System.out.println("Support for candidate general TGFD is below support threshold");
				continue;
			}

			System.out.println("TGFD Support = " + numerator + "/" + denominator);

			Delta delta = new Delta(Period.ofDays(generalMin * 183), Period.ofDays(generalMax * 183 + 1), Duration.ofDays(183));

			Dependency generalDependency = new Dependency();
			String yVertexType = literalPath.get(literalPath.size()-1).getVertexType();
			String yAttrName = literalPath.get(literalPath.size()-1).getAttrName();
			VariableLiteral y = new VariableLiteral(yVertexType, yAttrName, yVertexType, yAttrName);
			generalDependency.addLiteralToY(y);
			for (ConstantLiteral x : literalPath.subList(0, literalPath.size()-1)) {
				String xVertexType = x.getVertexType();
				String xAttrName = x.getAttrName();
				VariableLiteral varX = new VariableLiteral(xVertexType, xAttrName, xVertexType, xAttrName);
				generalDependency.addLiteralToX(varX);
			}

			TGFD tgfd = new TGFD(patternForDependency, delta, generalDependency, support, patternSupport, "");
			System.out.println("TGFD: " + tgfd);
			tgfds.add(tgfd);
		}
		return tgfds;
	}

	private ArrayList<TGFD> discoverConstantTGFDs(double theta, PatternTreeNode patternNode, ConstantLiteral yLiteral, Map<Set<ConstantLiteral>, ArrayList<Entry<ConstantLiteral, List<Integer>>>> entities, ArrayList<Pair> constantXdeltas, ArrayList<TreeSet<Pair>> satisfyingAttrValues) {
		ArrayList<TGFD> tgfds = new ArrayList<>();
		String yVertexType = yLiteral.getVertexType();
		String yAttrName = yLiteral.getAttrName();
		for (Entry<Set<ConstantLiteral>, ArrayList<Entry<ConstantLiteral, List<Integer>>>> entityEntry : entities.entrySet()) {
			VF2PatternGraph newPattern = patternNode.getPattern().copy();
			Dependency newDependency = new Dependency();
			ArrayList<ConstantLiteral> constantPath = new ArrayList<>();
			for (Vertex v : newPattern.getPattern().vertexSet()) {
				String vType = new ArrayList<>(v.getTypes()).get(0);
				if (vType.equalsIgnoreCase(yVertexType)) { // TO-DO: What if our pattern has duplicate vertex types?
					v.addAttribute(new Attribute(yAttrName));
					if (newDependency.getY().size() == 0) {
						VariableLiteral newY = new VariableLiteral(yVertexType, yAttrName, yVertexType, yAttrName);
						newDependency.addLiteralToY(newY);
					}
				}
				for (ConstantLiteral xLiteral : entityEntry.getKey()) {
					if (xLiteral.getVertexType().equalsIgnoreCase(vType)) {
						v.addAttribute(new Attribute(xLiteral.getAttrName(), xLiteral.getAttrValue()));
						ConstantLiteral newXLiteral = new ConstantLiteral(vType, xLiteral.getAttrName(), xLiteral.getAttrValue());
						newDependency.addLiteralToX(newXLiteral);
						constantPath.add(newXLiteral);
					}
				}
			}
			constantPath.add(new ConstantLiteral(yVertexType, yAttrName, null));

			System.out.println("Performing Constant TGFD discovery");
			System.out.println("Pattern: " + newPattern);
			System.out.println("Entity: " + newDependency);
			ArrayList<Entry<ConstantLiteral, List<Integer>>> attrValuesTimestampsSortedByFreq = entityEntry.getValue();

			for (Map.Entry<ConstantLiteral, List<Integer>> entry : attrValuesTimestampsSortedByFreq) {
				System.out.println(entry.getKey() + ":" + entry.getValue());
			}

			ArrayList<Pair> candidateDeltas = new ArrayList<>();
			if (attrValuesTimestampsSortedByFreq.size() == 1) {
				List<Integer> timestamps = attrValuesTimestampsSortedByFreq.get(0).getValue();
				int minDistance = this.numOfSnapshots - 1;
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
				int minExclusionDistance = this.numOfSnapshots - 1;
				int maxExclusionDistance = 0;
				ArrayList<Integer> distances = new ArrayList<>();
				int l1 = attrValuesTimestampsSortedByFreq.get(0).getValue().get(0);
				int u1 = attrValuesTimestampsSortedByFreq.get(0).getValue().get(attrValuesTimestampsSortedByFreq.get(0).getValue().size() - 1);
				for (int index = 1; index < attrValuesTimestampsSortedByFreq.size(); index++) {
					int l2 = attrValuesTimestampsSortedByFreq.get(index).getValue().get(0);
					int u2 = attrValuesTimestampsSortedByFreq.get(index).getValue().get(attrValuesTimestampsSortedByFreq.get(index).getValue().size() - 1);
					distances.add(Math.abs(u2 - l1));
					distances.add(Math.abs(u1 - l2));
				}
				for (int index = 0; index < distances.size(); index++) {
					minExclusionDistance = Math.min(minExclusionDistance, distances.get(index));
					maxExclusionDistance = Math.max(maxExclusionDistance, distances.get(index));
				}

				if (minExclusionDistance > 0) {
					Pair deltaPair = new Pair(0, minExclusionDistance - 1);
					candidateDeltas.add(deltaPair);
				}
				if (maxExclusionDistance < this.numOfSnapshots - 1) {
					Pair deltaPair = new Pair(maxExclusionDistance + 1, this.numOfSnapshots - 1);
					candidateDeltas.add(deltaPair);
				}
			}
			if (candidateDeltas.size() == 0) {
				System.out.println("Could not come up with any deltas for entity: " + entityEntry.getKey());
				continue;
			}

			// Compute TGFD support
			Delta candidateTGFDdelta = null;
			double candidateTGFDsupport = 0.0;
			Pair mostSupportedDelta = null;
			TreeSet<Pair> mostSupportedSatisfyingPairs = null;
			for (Pair candidateDelta : candidateDeltas) {
				int minDistance = candidateDelta.min();
				int maxDistance = candidateDelta.max();
				if (minDistance <= maxDistance) {
					System.out.println("Calculating support for candidate delta ("+minDistance+","+maxDistance+")");
					float numer = 0;
					float denom = 2 * 1 * this.numOfSnapshots;
					List<Integer> timestamps = attrValuesTimestampsSortedByFreq.get(0).getValue();
					TreeSet<Pair> satisfyingPairs = new TreeSet<Pair>();
					for (int index = 0; index < timestamps.size() - 1; index++) {
						for (int j = index + 1; j < timestamps.size(); j++) {
							if (timestamps.get(j) - timestamps.get(index) >= minDistance && timestamps.get(j) - timestamps.get(index) <= maxDistance) {
								satisfyingPairs.add(new Pair(timestamps.get(index), timestamps.get(j)));
							}
						}
					}

					System.out.println("Satisfying pairs: " + satisfyingPairs);

					numer = satisfyingPairs.size();
					double candidateSupport = numer / denom;

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
			satisfyingAttrValues.add(mostSupportedSatisfyingPairs);
			constantXdeltas.add(mostSupportedDelta);
			if (candidateTGFDsupport < theta) {
				System.out.println("Could not satisfy TGFD support threshold for entity: " + entityEntry.getKey());
				continue;
			}
			int minDistance = mostSupportedDelta.min();
			int maxDistance = mostSupportedDelta.max();
			candidateTGFDdelta = new Delta(Period.ofDays(minDistance * 183), Period.ofDays(maxDistance * 183 + 1), Duration.ofDays(183));
			System.out.println("Constant TGFD delta: "+candidateTGFDdelta);

			if (!isMinimalPath(constantPath, patternNode.getAllMinimalDependenciesOnThisPath())) {
				TGFD entityTGFD = new TGFD(newPattern, candidateTGFDdelta, newDependency, candidateTGFDsupport, patternNode.getPatternSupport(), "");
				System.out.println("TGFD: " + entityTGFD);
				tgfds.add(entityTGFD);
				patternNode.addMinimalDependency(constantPath);
			}
		}
		constantXdeltas.sort(new Comparator<Pair>() {
			@Override
			public int compare(Pair o1, Pair o2) {
				return o1.compareTo(o2);
			}
		});
		return tgfds;
	}

	public static ArrayList<DBPediaLoader> loadDBpediaSnapshots(String fileSuffix) {
		ArrayList<DBPediaLoader> graphs = new ArrayList<>();
		for (int year = 5; year < 8; year++) {
//			String fileSuffix = this.fileSuffix == null ? "" : "-" + this.fileSuffix;
			String typeFileName = "201" + year + "types" + fileSuffix + ".ttl";
			String literalsFileName = "201" + year + "literals" + fileSuffix + ".ttl";
			String objectsFileName = "201" + year + "objects" + fileSuffix + ".ttl";
			DBPediaLoader dbpedia = new DBPediaLoader(new ArrayList<>(), new ArrayList<>(Collections.singletonList(typeFileName)), new ArrayList<>(Arrays.asList(literalsFileName, objectsFileName)));
			graphs.add(dbpedia);
		}
		return graphs;
	}

	public ArrayList<TGFD> hSpawn(PatternTreeNode patternTreeNode, ArrayList<ArrayList<HashSet<ConstantLiteral>>> matchesPerTimestamps) {
		ArrayList<TGFD> tgfds = new ArrayList<>();

		System.out.println("Performing HSpawn for " + patternTreeNode.getPattern());

		HashSet<ConstantLiteral> literals = getActiveAttributesInPattern(patternTreeNode.getGraph().vertexSet());

		LiteralTree literalTree = new LiteralTree();
		for (int j = 0; j < literals.size(); j++) {

			System.out.println("HSpawn level " + j + "/" + literals.size());

			if (j == 0) {
				literalTree.addLevel();
				for (ConstantLiteral literal: literals) {
					literalTree.createNodeAtLevel(j, literal, null);
				}
			} else if (j <= currentVSpawnLevel) {
				ArrayList<LiteralTreeNode> literalTreePreviousLevel = literalTree.getLevel(j - 1);
				if (literalTreePreviousLevel.size() == 0) {
					System.out.println("Previous level of literal tree is empty. Nothing to expand. End HSpawn");
					break;
				}
				literalTree.addLevel();
				ArrayList<ArrayList<ConstantLiteral>> visitedPaths = new ArrayList<>();
				ArrayList<TGFD> currentLevelTGFDs = new ArrayList<>();
				for (LiteralTreeNode previousLevelLiteral : literalTreePreviousLevel) {
					System.out.println("Creating literal tree node " + literalTree.getLevel(j).size() + "/" + (literalTreePreviousLevel.size() * (literalTreePreviousLevel.size()-1)));
					if (previousLevelLiteral.isPruned()) continue;
					ArrayList<ConstantLiteral> parentsPathToRoot = previousLevelLiteral.getPathToRoot();
					for (ConstantLiteral literal: literals) {
						if (this.interestingTGFDs) { // Ensures all vertices are involved in dependency
							if (isUsedVertexType(literal.getVertexType(), parentsPathToRoot)) continue;
						} else { // Check if leaf node exists in path already
							if (parentsPathToRoot.contains(literal)) continue;
						}

						// Check if path to candidate leaf node is unique
						ArrayList<ConstantLiteral> newPath = new ArrayList<>();
						newPath.add(literal);
						newPath.addAll(previousLevelLiteral.getPathToRoot());
						if (isPathVisited(newPath, visitedPaths)) {
							System.out.println("Duplicate literal path: " + newPath);
							continue;
						}
						if (isZeroEntityPath(newPath, patternTreeNode) || isMinimalPath(newPath, patternTreeNode.getMinimalDependencies())) {
							System.out.println("Marking literal node as pruned. Skip dependency");
							continue;
						}
						System.out.println("Newly created unique literal path: " + newPath);

						// Add leaf node to tree
						LiteralTreeNode literalTreeNode = literalTree.createNodeAtLevel(j, literal, previousLevelLiteral);

						if (j != currentVSpawnLevel) { // Ensures delta discovery only occurs at |LHS| = |Q|
							System.out.println("|LHS| != |Q|. Skip performing Delta Discovery HSpawn level " + j);
							continue;
						}
						visitedPaths.add(newPath);

						System.out.println("Performing Delta Discovery at HSpawn level " + j);
						ArrayList<TGFD> discoveredTGFDs = deltaDiscovery(theta, patternTreeNode, literalTreeNode, newPath, matchesPerTimestamps);
						currentLevelTGFDs.addAll(discoveredTGFDs);
					}
				}
				if (currentLevelTGFDs.size() > 0) {
					System.out.println("TGFDs generated at HSpawn level " + j + ": " + currentLevelTGFDs.size());
					tgfds.addAll(currentLevelTGFDs);
				}
			} else {
				break;
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
	}

//	public void discover(int k, double theta, String experimentName) {
//		// TO-DO: Is it possible to prune the graphs by passing them our histogram's frequent nodes and edges as dummy TGFDs ???
////		Config.optimizedLoadingBasedOnTGFD = !tgfdDiscovery.isNaive;
//
//		PrintStream kExperimentResultsFile = null;
//		if (experimentName.startsWith("k")) {
//			try {
//				String timeAndDateStamp = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu.MM.dd.HH.mm.ss"));
//				kExperimentResultsFile = new PrintStream("k-experiments-runtimes-" + (isNaive ? "naive" : "optimized") + (interestingTGFDs ? "-interesting" : "") + "-" + timeAndDateStamp + ".txt");
//			} catch (FileNotFoundException e) {
//				e.printStackTrace();
//			}
//		}
//		final long kStartTime = System.currentTimeMillis();
//		loadDBpediaSnapshots();
//		for (int i = 0; i <= k; i++) {
//			ArrayList<TGFD> tgfds = vSpawn(i);
//
//			printTgfdsToFile(experimentName, i, theta, tgfds);
//
//			final long kEndTime = System.currentTimeMillis();
//			final long kRunTime = kEndTime - kStartTime;
//			if (experimentName.startsWith("k") && kExperimentResultsFile != null) {
//				System.out.println("Total execution time for k = " + k + " : " + kRunTime);
//				kExperimentResultsFile.print("k = " + i);
//				kExperimentResultsFile.println(", execution time = " + kRunTime);
//			}
//
//			System.gc();
//		}
//	}

	public void vSpawnInit() {

		this.genTree = new PatternTree();
		this.genTree.addLevel();

		List<Entry<String, Integer>> sortedNodeHistogram = getFrequentSortedVetexTypesHistogram();

		System.out.println("VSpawn Level 0");
		for (int i = 0; i < sortedNodeHistogram.size(); i++) {
			String vertexType = sortedNodeHistogram.get(i).getKey();

			if (this.vertexTypesAttributes.get(vertexType).size() < 2)
				continue; // TO-DO: Are we interested in TGFDs where LHS is empty?

			int numOfInstancesOfVertexType = sortedNodeHistogram.get(i).getValue();
			int numOfInstancesOfAllVertexTypes = this.NUM_OF_VERTICES_IN_GRAPH;

			double patternSupport = (double) numOfInstancesOfVertexType / (double) numOfInstancesOfAllVertexTypes;
			System.out.println("Estimate Pattern Support: " + patternSupport);

			System.out.println(vertexType);
			VF2PatternGraph candidatePattern = new VF2PatternGraph();
			PatternVertex vertex = new PatternVertex(vertexType);
			candidatePattern.addVertex(vertex);
			System.out.println("VSpawnInit with single-node pattern: " + candidatePattern);

			if (this.isNaive) {
				if (patternSupport >= this.patternSupportThreshold) {
					this.genTree.createNodeAtLevel(0, candidatePattern, patternSupport, null);
					System.out.println("Performing HSpawn");
				} else {
					System.out.println("Pattern support of " + patternSupport + " is below threshold.");
				}
			} else {
				this.genTree.createNodeAtLevel(0, candidatePattern, patternSupport, null);
				System.out.println("Performing HSpawn");
			}
			System.gc();
		}
		System.out.println("GenTree Level " + 0 + " size: " + this.genTree.getLevel(0).size());
		for (PatternTreeNode node : this.genTree.getLevel(0)) {
			System.out.println("Pattern: " + node.getPattern());
			System.out.println("Pattern Support: " + node.getPatternSupport());
//			System.out.println("Dependency: " + node.getDependenciesSets());
		}

	}

	public static boolean isDuplicateEdge(VF2PatternGraph pattern, String edgeType, String type1, String type2) {
		for (RelationshipEdge edge : pattern.getPattern().edgeSet()) {
			if (edge.getLabel().equalsIgnoreCase(edgeType)) {
				if (edge.getSource().getTypes().contains(type1) && edge.getTarget().getTypes().contains(type2)) {
					return true;
				}
			}
		}
		return false;
	}

	public PatternTreeNode vSpawn() {

		List<Entry<String, Integer>> sortedUniqueEdgesHist = getSortedEdgeHistogram();

		if (this.candidateEdgeIndex > sortedUniqueEdgesHist.size()-1) {
			this.candidateEdgeIndex = 0;
			this.previousLevelNodeIndex++;
		}

		if (this.previousLevelNodeIndex >= this.genTree.getLevel(this.currentVSpawnLevel -1).size()) {
			this.printTgfdsToFile("api-test", this.tgfds.get(this.currentVSpawnLevel));
			this.currentVSpawnLevel++;
			this.genTree.addLevel();
			this.previousLevelNodeIndex = 0;
			this.candidateEdgeIndex = 0;
		}

		System.out.println("Performing VSpawn");
		System.out.println("VSpawn Level " + this.currentVSpawnLevel);
		System.gc();

		ArrayList<PatternTreeNode> previousLevel = this.genTree.getLevel(this.currentVSpawnLevel - 1);

		PatternTreeNode previousLevelNode = previousLevel.get(this.previousLevelNodeIndex);
		System.out.println("Processing previous level node " + this.previousLevelNodeIndex + "/" + previousLevel.size());
		System.out.println("Performing VSpawn on pattern: " + previousLevelNode.getPattern());

		System.out.println("Level " + (this.currentVSpawnLevel - 1) + " pattern: " + previousLevelNode.getPattern());
		if (previousLevelNode.isPruned() && !this.isNaive) {
			System.out.println("Marked as pruned. Skip.");
			this.previousLevelNodeIndex++;
			return null;
		}

		System.out.println("Processing candidate edge " + this.candidateEdgeIndex + "/" + sortedUniqueEdgesHist.size());
		Map.Entry<String, Integer> candidateEdge = sortedUniqueEdgesHist.get(this.candidateEdgeIndex);
		String candidateEdgeString = candidateEdge.getKey();
		System.out.println("Candidate edge:" + candidateEdgeString);
		if (this.isNaive && (1.0 * uniqueEdgesHist.get(candidateEdgeString) / NUM_OF_EDGES_IN_GRAPH) < this.patternSupportThreshold) {
			System.out.println("Below pattern support threshold. Skip");
			this.candidateEdgeIndex++;
			return null;
		}
		String sourceVertexType = candidateEdgeString.split(" ")[0];
		String targetVertexType = candidateEdgeString.split(" ")[2];
		// TO-DO: We should add support for this in the future
		if (sourceVertexType.equals(targetVertexType)) {
			System.out.println("Candidate edge contains duplicate vertex types. Skip.");
			this.candidateEdgeIndex++;
			return null;
		}
		String edgeType = candidateEdgeString.split(" ")[1];

		PatternTreeNode patternTreeNode = null;
		// TO-DO: FIX label conflict. What if an edge has same vertex type on both sides?
		for (Vertex v : previousLevelNode.getGraph().vertexSet()) {
			if (v.isMarked()) {
				System.out.println("Skip. Already added candidate edge to vertex: " + v.getTypes());
				continue;
			}
			System.out.println("Looking to add candidate edge to vertex: " + v.getTypes());
			if (!v.getTypes().contains(sourceVertexType)) {
				System.out.println("Candidate edge does not connect to vertex: " + v.getTypes());
				continue;
			}
//					if (v.getTypes().contains(sourceVertexType) || v.getTypes().contains(targetVertexType)) {
			// Create copy of k-1 pattern
			VF2PatternGraph newPattern = previousLevelNode.getPattern().copy();
			if (isDuplicateEdge(newPattern, edgeType, sourceVertexType, targetVertexType)) {
				System.out.println("Candidate edge: " + candidateEdge.getKey());
				System.out.println("already exists in pattern");
				continue;
			}
//					if (v.getTypes().contains(sourceVertexType)) {
				if (this.vertexTypesAttributes.get(targetVertexType).size() == 0) continue;
				PatternVertex node2 = new PatternVertex(targetVertexType);
				newPattern.addVertex(node2);
				RelationshipEdge newEdge = new RelationshipEdge(edgeType);
				PatternVertex node1 = null;
				for (Vertex vertex : newPattern.getPattern().vertexSet()) {
					if (vertex.getTypes().contains(sourceVertexType)) {
						node1 = (PatternVertex) vertex;
					}
				}
				newPattern.addEdge(node1, node2, newEdge);
//					}
			// TO-DO: Support the addition of incoming edges into new patterns
//					else if (v.getTypes().contains(targetVertexType)) {
//						PatternVertex node1 = new PatternVertex(sourceVertexType);
//						newPattern.addVertex(node1);
//						RelationshipEdge newEdge = new RelationshipEdge(edgeType);
//						PatternVertex node2 = null;
//						for (Vertex vertex : newPattern.getPattern().vertexSet()) {
//							if (vertex.getTypes().contains(targetVertexType)) {
//								node2 = (PatternVertex) vertex;
//							}
//						}
//						newPattern.addEdge(node1, node2, newEdge);
//					}
			System.out.println("Created new pattern: " + newPattern);

			double numerator = Double.MAX_VALUE;
			double denominator = NUM_OF_EDGES_IN_GRAPH;

			for (RelationshipEdge tempE : newPattern.getPattern().edgeSet()) {
				String sourceType = (new ArrayList<>(tempE.getSource().getTypes())).get(0);
				String targetType = (new ArrayList<>(tempE.getTarget().getTypes())).get(0);
				String uniqueEdge = sourceType + " " + tempE.getLabel() + " " + targetType;
				numerator = Math.min(numerator, uniqueEdgesHist.get(uniqueEdge));
			}
			assert numerator <= denominator;
			double estimatedPatternSupport = numerator / denominator;
			System.out.println("Estimate Pattern Support: " + estimatedPatternSupport);

			patternTreeNode = this.genTree.createNodeAtLevel(this.currentVSpawnLevel, newPattern, estimatedPatternSupport, previousLevelNode);
			v.setMarked(true);
			System.out.println("Marking vertex " + v.getTypes() + "as expanded.");
			break;
		}
		if (patternTreeNode == null) {
			for (Vertex v : previousLevelNode.getGraph().vertexSet()) {
				System.out.println("Unmarking all vertices in current pattern for the next candidate edge");
				v.setMarked(false);
			}
			this.candidateEdgeIndex++;
		}
		return patternTreeNode;
	}

	public int getMatchesForPattern(ArrayList<DBPediaLoader> graphs, PatternTreeNode patternTreeNode, ArrayList<ArrayList<HashSet<ConstantLiteral>>> matchesPerTimestamps) {
		// TO-DO: Potential speed up for single-edge/single-node patterns. Iterate through all edges/nodes in graph.
		HashSet<ConstantLiteral> activeAttributesInPattern = getActiveAttributesInPattern(patternTreeNode.getGraph().vertexSet());
		for (int year = 0; year < this.numOfSnapshots; year++) {
			VF2AbstractIsomorphismInspector<Vertex, RelationshipEdge> results = new VF2SubgraphIsomorphism().execute2(graphs.get(year).getGraph(), patternTreeNode.getPattern(), false);
			ArrayList<HashSet<ConstantLiteral>> matches = new ArrayList<>();
			if (results.isomorphismExists()) {
				Iterator<GraphMapping<Vertex, RelationshipEdge>> iterator = results.getMappings();
				while (iterator.hasNext()) {
					GraphMapping<Vertex, RelationshipEdge> result = iterator.next();
					HashSet<ConstantLiteral> match = new HashSet<>();
					for (Vertex v : patternTreeNode.getGraph().vertexSet()) {
						Vertex currentMatchedVertex = result.getVertexCorrespondence(v, false);
						if (currentMatchedVertex == null) continue;
						String patternVertexType = new ArrayList<>(currentMatchedVertex.getTypes()).get(0);
						for (ConstantLiteral attribute : activeAttributesInPattern) {
							if (!attribute.getVertexType().equals(patternVertexType)) continue;
							for (String attrName : currentMatchedVertex.getAllAttributesNames()) {
								if (!attribute.getAttrName().equals(attrName)) continue;
								String xAttrValue = currentMatchedVertex.getAttributeValueByName(attrName);
								ConstantLiteral xLiteral = new ConstantLiteral(patternVertexType, attrName, xAttrValue);
								match.add(xLiteral);
							}
						}
					}
					if (match.size() == 0) continue;
					matches.add(match);
				}
				System.out.println("Number of matches found in " + (2015 + year) + ": " + matches.size());
				matches.sort(new Comparator<HashSet<ConstantLiteral>>() {
					@Override
					public int compare(HashSet<ConstantLiteral> o1, HashSet<ConstantLiteral> o2) {
						return o1.size() - o2.size();
					}
				});
			}
			matchesPerTimestamps.get(year).addAll(matches);
		}
		// TO-DO: Should we implement pattern support here to weed out patterns with few matches in later iterations?
		// Is there an ideal pattern support threshold after which very few TGFDs are discovered?
		// How much does the real pattern differ from the estimate?
		double numberOfMatchesFound = 0;
		for (ArrayList<HashSet<ConstantLiteral>> matchesInOneTimestamp : matchesPerTimestamps) {
			numberOfMatchesFound += matchesInOneTimestamp.size();
		}
		if (numberOfMatchesFound == 0) {
			System.out.println("Mark as pruned. No matches found for pattern " + patternTreeNode.getPattern());
			patternTreeNode.setIsPruned();
//			return tgfds;
		}

		// TO-DO: Use this instead of estimated patterns support?
		double realPatternSupport = numberOfMatchesFound / NUM_OF_EDGES_IN_GRAPH;
		System.out.println("Real Pattern Support: " + realPatternSupport);
		return (int) numberOfMatchesFound;
	}

}
