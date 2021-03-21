package util;

import java.io.File;
import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;

public class configParser {

    HashMap<Integer, ArrayList<String>> typesPaths = new HashMap<>();
    HashMap<Integer, ArrayList<String>> dataPaths = new HashMap<>();
    private HashMap <Integer, String> diffFilesPath=new HashMap<>();
    private String patternPath = "";
    private HashMap<Integer,LocalDate> timestamps=new HashMap<>();

    public configParser(String pathToConfigFile) throws FileNotFoundException {
        parseInputParams(pathToConfigFile);
    }

    /**
     * Expected arguments to parse:
     * -p <patternFile>
     * [-t<snapshotId> <typeFile>]
     * [-d<snapshotId> <dataFile>]
     * [-c<snapshotId> <diff file>]
     * [-s<snapshotId> <snapshot timestamp>]
     * -optgraphload <true-false> // load parts of data file that are needed based on the TGFDs
     * -debug <true-false> // print details of matching
     *
     * TODO: check correctness, e.g. typesPaths.keySet == dataPaths.keySet [2021-03-18]
     */
    private void parseInputParams(String pathToConfigFile) throws FileNotFoundException {
        Scanner scanner = new Scanner(new File(pathToConfigFile));
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            String []conf=line.split(" ");
            if(conf.length!=2)
                continue;
            if (conf[0].toLowerCase().startsWith("-t"))
            {
                var snapshotId = Integer.parseInt(conf[0].substring(2));
                if (!typesPaths.containsKey(snapshotId))
                    typesPaths.put(snapshotId, new ArrayList <>());
                typesPaths.get(snapshotId).add(conf[1]);
            }
            else if (conf[0].toLowerCase().startsWith("-d"))
            {
                var snapshotId = Integer.parseInt(conf[0].substring(2));
                if (!dataPaths.containsKey(snapshotId))
                    dataPaths.put(snapshotId, new ArrayList <>());
                dataPaths.get(snapshotId).add(conf[1]);
            }
            else if (conf[0].toLowerCase().startsWith("-c"))
            {
                var snapshotId = Integer.parseInt(conf[0].substring(2));
                if(snapshotId!=1)
                    diffFilesPath.put(snapshotId, conf[1]);
            }
            else if (conf[0].toLowerCase().startsWith("-p"))
            {
                patternPath = conf[1];
            }
            else if (conf[0].toLowerCase().startsWith("-s"))
            {
                var snapshotId = Integer.parseInt(conf[0].substring(2));
                timestamps.put(snapshotId, LocalDate.parse(conf[1]));
            }
            else if(conf[0].toLowerCase().startsWith("-optgraphload"))
            {
                properties.myProperties.optimizedLoadingBasedOnTGFD=Boolean.parseBoolean(conf[1]);
            }
            else if(conf[0].toLowerCase().startsWith("-debug"))
            {
                properties.myProperties.printDetailedMatchingResults=Boolean.parseBoolean(conf[1]);
            }
        }
    }

    public ArrayList <String> getFirstDataFilePath() {
        return dataPaths.get(1);
    }

    public ArrayList <String> getFirstTypesFilePath() {
        return typesPaths.get(1);
    }

    public HashMap <Integer, ArrayList <String>> getAllDataPaths() {
        return dataPaths;
    }

    public HashMap <Integer, ArrayList <String>> getAllTypesPaths() {
        return typesPaths;
    }

    public HashMap <Integer, LocalDate> getTimestamps() {
        return timestamps;
    }

    public HashMap <Integer, String> getDiffFilesPath() {
        return diffFilesPath;
    }

    public String getPatternPath() {
        return patternPath;
    }
}
