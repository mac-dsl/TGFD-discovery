import graphLoader.IMDBLoader;
import org.apache.commons.cli.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static TgfdDiscovery.TgfdDiscovery.generateImdbTimestampToFilesMapFromPath;

public class generateCustomImdb {

    public static final double LOWER_THRESHOLD = 0.01;
    public static final double UPPER_THRESHOLD = 0.01;

    public static void main(String[] args) throws ParseException {
        Options options = new Options();
        options.addOption("path", true, "generate graphs using files from specified path");
        options.addOption("rdfType", true, "output data files using specified extension");
        options.addOption("count", true, "generate graphs based on vertex count");
        options.addOption("t", true, "generate graphs with t snapshots");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String path = null;
        if (cmd.hasOption("path")) {
            path = cmd.getOptionValue("path").replaceFirst("^~", System.getProperty("user.home"));
            if (!Files.isDirectory(Path.of(path))) {
                System.out.println(Path.of(path) + " is not a valid directory.");
                return;
            }
        }
        String rdfType = "N-TRIPLE";
        if (cmd.hasOption("rdfType")) {
            rdfType = cmd.getOptionValue("rdfType");
        }

        int t = 0;
        if (cmd.hasOption("t")) {
            t = Integer.parseInt(cmd.getOptionValue("t"));
        }
        List<Map.Entry<String, List<String>>> timestampToFilesMap = new ArrayList<>(generateImdbTimestampToFilesMapFromPath(path).entrySet());
        timestampToFilesMap.sort(Map.Entry.comparingByKey());

        if (cmd.hasOption("count")) {
            String[] sizes = cmd.getOptionValue("count").split(",");
            generateCustomDBpediaBasedOnSize2(sizes, timestampToFilesMap.subList(0, t), rdfType);
        }
    }

    public static void generateCustomDBpediaBasedOnSize2(String[] sizesStrings, List<Map.Entry<String, List<String>>> timestampToFilesMap, String rdfType) {
        long[] sizes = new long[sizesStrings.length];
        for (int index = 0; index < sizes.length; index++) {
            sizes[index] = Long.parseLong(sizesStrings[index]);
        }

        for (Map.Entry<String, List<String>> timestampToFilesEntry : timestampToFilesMap) {
            Model model = ModelFactory.createDefaultModel();
            String filePath = "";
            for (String filename : timestampToFilesEntry.getValue()) {
                filePath = filename;
            }
            Map<String, Set<Statement>> vertexURIToStmtsMap = new HashMap<>();
            Map<String, Set<String>> vertexURIToTypesMap = new HashMap<>();
            Map<String, List<Statement>> edgeTypeToStmtsMap = new HashMap<>();
            for (int j = 0; j < 2; j++) {
//                final String filePath = path + "/201" + (i + 5) + "/201" + (i + 5) + fileType + ".ttl";
                System.out.print("Processing " + filePath + ". ");
                Path input = Paths.get(filePath);
                model.read(input.toAbsolutePath().toString());
                StmtIterator stmtIterator = model.listStatements();
                switch (j) {
                    case 0 -> {
                        while (stmtIterator.hasNext()) {
                            Statement stmt = stmtIterator.nextStatement();
                            String subjectNodeURI = stmt.getSubject().getURI().toLowerCase();
                            if (subjectNodeURI.length() > 16) {
                                subjectNodeURI = subjectNodeURI.substring(16);
                            }

                            var temp = subjectNodeURI.split("/");
                            if (temp.length != 2) {
                                // Error!
                                continue;
                            }
                            String subjectType = temp[0];
                            vertexURIToTypesMap.putIfAbsent(subjectNodeURI, new HashSet<>());
                            vertexURIToTypesMap.get(subjectNodeURI).add(subjectType);
                            vertexURIToStmtsMap.putIfAbsent(subjectNodeURI, new HashSet<>());
                            vertexURIToStmtsMap.get(subjectNodeURI).add(stmt);
                            if (stmt.getObject().isLiteral()) continue;
                            String objectNodeURI = stmt.getObject().asResource().getURI().toLowerCase();
                            if (objectNodeURI.length() > 16) {
                                objectNodeURI = objectNodeURI.substring(16);
                            }

                            temp = objectNodeURI.split("/");
                            if (temp.length != 2) {
                                // Error!
                                continue;
                            }
                            String objectType = temp[0];
                            vertexURIToTypesMap.putIfAbsent(objectNodeURI, new HashSet<>());
                            vertexURIToTypesMap.get(objectNodeURI).add(objectType);
                        }
                    }
                    case 1 -> {
                        while (stmtIterator.hasNext()) {
                            Statement stmt = stmtIterator.nextStatement();
                            if (!stmt.getObject().isLiteral()) {
                                String subjectNodeURI = stmt.getSubject().getURI().toLowerCase();
                                if (subjectNodeURI.length() > 16) {
                                    subjectNodeURI = subjectNodeURI.substring(16);
                                }
                                Set<String> subjectTypes = vertexURIToTypesMap.get(subjectNodeURI);
                                String objectNodeURI = stmt.getSubject().getURI().toLowerCase();
                                if (objectNodeURI.length() > 16) {
                                    objectNodeURI = objectNodeURI.substring(16);
                                }
                                Set<String> objectTypes = vertexURIToTypesMap.get(objectNodeURI);
                                String predicateName = stmt.getPredicate().getLocalName();
                                if (subjectTypes == null || objectTypes == null) continue;
                                for (String subjectType : subjectTypes) {
                                    for (String objectType : objectTypes) {
                                        String edgeType = String.join(",", Arrays.asList(subjectType, predicateName, objectType));
                                        edgeTypeToStmtsMap.putIfAbsent(edgeType, new ArrayList<>());
                                        edgeTypeToStmtsMap.get(edgeType).add(stmt);
                                    }
                                }
                            } else {
                                String subjectURI = stmt.getSubject().getURI().toLowerCase();
                                vertexURIToStmtsMap.putIfAbsent(subjectURI, new HashSet<>());
                                vertexURIToStmtsMap.get(subjectURI).add(stmt);
                            }
                        }
                    }
                }
            }
            System.out.println("Done");
            // TO-DO: Verify if this helps make snapshots consistent
            for (Map.Entry<String, List<Statement>> entry: edgeTypeToStmtsMap.entrySet()) {
                entry.getValue().sort(new Comparator<Statement>() {
                    @Override
                    public int compare(Statement o1, Statement o2) {
                        int result = o1.getSubject().getURI().toLowerCase().compareTo(o2.getSubject().getURI().toLowerCase());
                        if (result == 0) {
                            result = o1.getPredicate().getLocalName().compareTo(o2.getPredicate().getLocalName());
                        }
                        if (result == 0) {
                            result = o1.getObject().asResource().getURI().toLowerCase().compareTo(o2.getObject().asResource().getURI().toLowerCase());
                        }
                        return result;
                    }
                });
            }
            ArrayList<Map.Entry<String, List<Statement>>> sortedList = new ArrayList<>(edgeTypeToStmtsMap.entrySet());
            sortedList.sort(new Comparator<Map.Entry<String, List<Statement>>>() {
                @Override
                public int compare(Map.Entry<String, List<Statement>> o1, Map.Entry<String, List<Statement>> o2) {
                    return o1.getValue().size() - o2.getValue().size();
                }
            });
            List<Double> percentagesForThisTimestamp = new ArrayList<>();
            for (long size : sizes) {
                System.out.println("Size: " + size);
                double total = 0;
                double percent = 0.00;
                double increment = 0.01;
                while ((total < (size - (size * LOWER_THRESHOLD)) || total > (size + (size * UPPER_THRESHOLD))) && percent <= 1.0) {
                    if (total < (size - (size * LOWER_THRESHOLD))) {
                        System.out.println("Not enough edges");
                        percent += increment;
                    } else if (total > (size + (size * UPPER_THRESHOLD))) {
                        System.out.println("Too many edges");
                        percent -= increment;
                        increment /= 10;
                        percent += increment;
                    }
//                    percent += 0.01;
                    System.out.println("Trying percent: " + percent);
                    total = 0;
                    for (Map.Entry<String, List<Statement>> entry : sortedList) {
                        Iterator<Statement> stmtIterator = entry.getValue().iterator();
                        int singleEdgeTypeCount = 0;
                        while (singleEdgeTypeCount + 1 < (entry.getValue().size() * percent) && stmtIterator.hasNext()) {
                            stmtIterator.next();
                            singleEdgeTypeCount++;
                        }
//                        total += (entry.getValue().size() * percent);
                        total += singleEdgeTypeCount;
                    }
                    System.out.println("Total: " + total);
                }
                percentagesForThisTimestamp.add(percent);
            }
            System.out.println(percentagesForThisTimestamp);
            for (int j = 0; j < sizes.length; j++) {
                long size = sizes[j];
                String directoryStructure = "imdb-" + size + "/";
                String fileExtension = rdfType.equalsIgnoreCase("N-TRIPLE") ? ".nt" : ".ttl";
                String newFileName = directoryStructure + "imdb-" + timestampToFilesEntry.getKey() + fileExtension;
                System.out.println("Creating model for " + newFileName);
                double percentage = percentagesForThisTimestamp.get(j);
                Model newModel = ModelFactory.createDefaultModel();
                int totalEdgeCount = 0;
                int totalAttributeCount = 0;
                for (Map.Entry<String, List<Statement>> edgeTypeToStmtsMapEntry : edgeTypeToStmtsMap.entrySet()) {
                    Iterator<Statement> stmtIterator = edgeTypeToStmtsMapEntry.getValue().iterator();
                    int singleEdgeTypeCount = 0;
                    while (singleEdgeTypeCount + 1 < (edgeTypeToStmtsMapEntry.getValue().size() * percentage) && stmtIterator.hasNext()) {
                        Statement stmt = stmtIterator.next();
                        String subjectURI = stmt.getSubject().getURI().toLowerCase();
                        String objectURI = stmt.getObject().asResource().getURI().toLowerCase();
                        newModel.add(stmt);
                        if (vertexURIToStmtsMap.containsKey(subjectURI)) {
                            Set<Statement> subjectStmts = vertexURIToStmtsMap.get(subjectURI);
                            for (Statement subjStmt : subjectStmts) {
                                if (subjStmt.getObject().isLiteral() || subjStmt.getPredicate().getLocalName().contains("type")) {
                                    newModel.add(subjStmt);
                                    if (!subjStmt.getPredicate().getLocalName().contains("type")) {
                                        totalAttributeCount++;
                                    }
                                }
                            }
                        }
                        if (vertexURIToStmtsMap.containsKey(objectURI)) {
                            Set<Statement> objectStmts = vertexURIToStmtsMap.get(objectURI);
                            for (Statement objStmt : objectStmts) {
                                if (objStmt.getObject().isLiteral() || objStmt.getPredicate().getLocalName().contains("type")) {
                                    newModel.add(objStmt);
                                    if (!objStmt.getPredicate().getLocalName().contains("type")) {
                                        totalAttributeCount++;
                                    }
                                }
                            }
                        }
                        singleEdgeTypeCount++;
                    }
                    totalEdgeCount += singleEdgeTypeCount;
                }
                System.out.println("Edge count = " + totalEdgeCount);
                System.out.println("Attribute count = " + totalAttributeCount);
                try {
                    System.out.print("Writing to " + newFileName + ". ");
                    Files.createDirectories(Paths.get(directoryStructure));
                    newModel.write(new PrintStream(newFileName), rdfType.toUpperCase());
                    System.out.println("Done.");
                    new IMDBLoader(new ArrayList<>(), Collections.singletonList(newModel));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
