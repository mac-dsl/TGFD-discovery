package IncrementalRunner;

import VF2Runner.VF2SubgraphIsomorphism;
import changeExploration.*;
import infra.*;
import org.jgrapht.Graph;
import org.jgrapht.GraphMapping;

import java.util.*;

public class IncUpdates {

    private VF2DataGraph baseGraph;
    private VF2SubgraphIsomorphism VF2;

    /** Map of the relevant TGFDs for each entity type */
    private HashMap<String, HashSet<String>> relaventTGFDs=new HashMap <>();


    public IncUpdates(VF2DataGraph baseGraph, List<TGFD> tgfds)
    {
        this.baseGraph=baseGraph;
        this.VF2= new VF2SubgraphIsomorphism();

        for (TGFD tgfd:tgfds) {
            extractValidTypesFromTGFD(tgfd);
        }
    }

    public HashMap<String,IncrementalChange> updateGraph(Change change, HashMap<String,TGFD> tgfdsByName)
    {
        // Remove TGFDs from the Affected TGFD lists of the change if that TGFD is not loaded.
        change.getTGFDs().removeIf(TGFDName -> !tgfdsByName.containsKey(TGFDName));

        if(change instanceof EdgeChange)
        {
            EdgeChange edgeChange=(EdgeChange) change;
            DataVertex v1= (DataVertex) baseGraph.getNode(edgeChange.getSrc());
            DataVertex v2= (DataVertex) baseGraph.getNode(edgeChange.getDst());
            if(v1==null || v2==null)
            {
                // Node doesn't exist in the base graph, we need to igonre the change
                // We keep the number of these ignored edges in a variable
                return null;
            }
            // If TGFD list in the change is empty (specifically for synthetic graph),
            // we need to find the relevant TGFDs and add to the TGFD list
            if(change.getTGFDs().size()==0)
            {
                findRelevantTGFDs(edgeChange,v1);
                findRelevantTGFDs(edgeChange,v2);
            }

            if(edgeChange.getTypeOfChange()== ChangeType.insertEdge)
                return updateGraphByAddingNewEdge(v1,v2,new RelationshipEdge(edgeChange.getLabel()),change.getTGFDs(),tgfdsByName);
            else if(edgeChange.getTypeOfChange()== ChangeType.deleteEdge)
                return updateGraphByDeletingAnEdge(v1,v2,new RelationshipEdge(edgeChange.getLabel()),change.getTGFDs(),tgfdsByName);
            else
                throw new IllegalArgumentException("The change is instnace of EdgeChange, but type of change is: " + edgeChange.getTypeOfChange());
        }
        else if(change instanceof AttributeChange)
        {
            AttributeChange attributeChange=(AttributeChange) change;
            DataVertex v1=(DataVertex) baseGraph.getNode(attributeChange.getUri());
            if(v1==null)
            {
                // Node doesn't exist in the base graph, we need to igonre the change
                // We store the number of these ignored changes
                return null;
            }
            // If TGFD list in the change is empty (specifically for synthetic graph),
            // we need to find the relevant TGFDs and add to the TGFD list
            if(change.getTGFDs().size()==0)
            {
                findRelevantTGFDs(attributeChange,v1);
            }
            if(attributeChange.getTypeOfChange()==ChangeType.changeAttr || attributeChange.getTypeOfChange()==ChangeType.insertAttr)
            {
                return updateGraphByUpdatingAnAttribute(v1,attributeChange.getAttribute(),change.getTGFDs(),tgfdsByName);
            }
            else if(attributeChange.getTypeOfChange()==ChangeType.deleteAttr)
            {
                return updateGraphByDeletingAnAttribute(v1,attributeChange.getAttribute(),change.getTGFDs(),tgfdsByName);
            }
            else
                throw new IllegalArgumentException("The change is instnace of AttributeChange, but type of change is: " + attributeChange.getTypeOfChange());
        }
        else
            return null;
    }

    public void AddNewVertices(List<Change> allChange)
    {
        for (Change change:allChange) {
            if(change instanceof VertexChange && change.getTypeOfChange()==ChangeType.insertVertex)
            {
                baseGraph.addVertex(((VertexChange) change).getVertex());
            }
        }
    }

    private HashMap<String,IncrementalChange> updateGraphByAddingNewEdge(
            DataVertex v1, DataVertex v2, RelationshipEdge edge,Set <String> affectedTGFDNames, HashMap<String,TGFD> tgfdsByName)
    {
        HashMap<String,IncrementalChange> incrementalChangeHashMap=new HashMap <>();
        Graph<Vertex, RelationshipEdge> subgraph= baseGraph.getSubGraphByDiameter(v1,getDiameter(affectedTGFDNames,tgfdsByName));

        // run VF2
        for (String tgfdName:affectedTGFDNames) {

            Iterator<GraphMapping<Vertex, RelationshipEdge>> beforeChange = VF2.execute(subgraph,tgfdsByName.get(tgfdName).getPattern(),false);
            IncrementalChange incrementalChange=new IncrementalChange(beforeChange,tgfdsByName.get(tgfdName).getPattern());
            incrementalChangeHashMap.put(tgfdsByName.get(tgfdName).getName(),incrementalChange);
        }
        //perform the change...
        if(!subgraph.containsVertex(v2))
        {
            subgraph.addVertex(v2);
        }
        subgraph.addEdge(v1,v2,edge);
        baseGraph.addEdge(v1, v2,edge);

        // Run VF2 again...
        for (String tgfdName:affectedTGFDNames) {
            Iterator<GraphMapping<Vertex, RelationshipEdge>> afterChange = VF2.execute(subgraph,tgfdsByName.get(tgfdName).getPattern(),false);
            incrementalChangeHashMap.get(tgfdsByName.get(tgfdName).getName()).addAfterMatches(afterChange);
        }

        return incrementalChangeHashMap;
    }

    private HashMap<String,IncrementalChange> updateGraphByDeletingAnEdge(
            DataVertex v1, DataVertex v2, RelationshipEdge edge,Set <String> affectedTGFDNames, HashMap<String,TGFD> tgfdsByName)
    {

        HashMap<String,IncrementalChange> incrementalChangeHashMap=new HashMap <>();
        Graph<Vertex, RelationshipEdge> subgraph= baseGraph.getSubGraphByDiameter(v1,getDiameter(affectedTGFDNames,tgfdsByName));

        // run VF2
        for (String tgfdName:affectedTGFDNames) {
            Iterator<GraphMapping<Vertex, RelationshipEdge>> beforeChange = VF2.execute(subgraph,tgfdsByName.get(tgfdName).getPattern(),false);
            IncrementalChange incrementalChange=new IncrementalChange(beforeChange,tgfdsByName.get(tgfdName).getPattern());
            incrementalChangeHashMap.put(tgfdsByName.get(tgfdName).getName(),incrementalChange);
        }

        // Now, perform the change and remove the edge from the subgraph
        for (RelationshipEdge e:subgraph.outgoingEdgesOf(v1)) {
            DataVertex target=(DataVertex) e.getTarget();
            if(target.getVertexURI().equals(v2.getVertexURI()) && edge.getLabel().equals(e.getLabel()))
            {
                subgraph.removeEdge(e);
                break;
            }
        }
        //remove from the base graph.
        baseGraph.removeEdge(v1,v2,edge);


        // Run VF2 again...
        for (String tgfdName:affectedTGFDNames) {
            Iterator<GraphMapping<Vertex, RelationshipEdge>> afterChange = VF2.execute(subgraph,tgfdsByName.get(tgfdName).getPattern(),false);
            incrementalChangeHashMap.get(tgfdsByName.get(tgfdName).getName()).addAfterMatches(afterChange);
        }
        return incrementalChangeHashMap;
    }

    private HashMap<String,IncrementalChange> updateGraphByUpdatingAnAttribute(
            DataVertex v1, Attribute attribute,Set <String> affectedTGFDNames, HashMap<String,TGFD> tgfdsByName)
    {
        HashMap<String,IncrementalChange> incrementalChangeHashMap=new HashMap <>();
        Graph<Vertex, RelationshipEdge> subgraph= baseGraph.getSubGraphByDiameter(v1,getDiameter(affectedTGFDNames,tgfdsByName));

        // run VF2
        for (String tgfdName:affectedTGFDNames) {
            Iterator<GraphMapping<Vertex, RelationshipEdge>> beforeChange = VF2.execute(subgraph,tgfdsByName.get(tgfdName).getPattern(),false);
            IncrementalChange incrementalChange=new IncrementalChange(beforeChange,tgfdsByName.get(tgfdName).getPattern());
            incrementalChangeHashMap.put(tgfdsByName.get(tgfdName).getName(),incrementalChange);

        }

        //Now, perform the change...
        v1.setOrAddAttribute(attribute);

        // Run VF2 again...
        for (String tgfdName:affectedTGFDNames) {
            Iterator<GraphMapping<Vertex, RelationshipEdge>> afterChange = VF2.execute(subgraph,tgfdsByName.get(tgfdName).getPattern(),false);
            incrementalChangeHashMap.get(tgfdsByName.get(tgfdName).getName()).addAfterMatches(afterChange);
        }
        return incrementalChangeHashMap;
    }

    private HashMap<String,IncrementalChange> updateGraphByDeletingAnAttribute(
            DataVertex v1, Attribute attribute,Set <String> affectedTGFDNames, HashMap<String,TGFD> tgfdsByName)
    {
        HashMap<String,IncrementalChange> incrementalChangeHashMap=new HashMap <>();
        Graph<Vertex, RelationshipEdge> subgraph= baseGraph.getSubGraphByDiameter(v1,getDiameter(affectedTGFDNames,tgfdsByName));

        // run VF2
        for (String tgfdName:affectedTGFDNames) {
            Iterator<GraphMapping<Vertex, RelationshipEdge>> beforeChange = VF2.execute(subgraph,tgfdsByName.get(tgfdName).getPattern(),false);
            IncrementalChange incrementalChange=new IncrementalChange(beforeChange,tgfdsByName.get(tgfdName).getPattern());
            incrementalChangeHashMap.put(tgfdsByName.get(tgfdName).getName(),incrementalChange);
        }

        //Now, perform the change...
        v1.deleteAttribute(attribute);

        // Run VF2 again...
        for (String tgfdName:affectedTGFDNames) {
            Iterator<GraphMapping<Vertex, RelationshipEdge>> afterChange = VF2.execute(subgraph,tgfdsByName.get(tgfdName).getPattern(),false);
            incrementalChangeHashMap.get(tgfdsByName.get(tgfdName).getName()).addAfterMatches(afterChange);
        }

        return incrementalChangeHashMap;
    }

    private int getDiameter(Set <String> affectedTGFDNames, HashMap<String,TGFD> tgfdsByName)
    {
        int maxDiameter=0;
        for (String tgfdName:affectedTGFDNames) {
            return tgfdsByName.get(tgfdName).getPattern().getDiameter();
        }
        return maxDiameter;
    }

    private void findRelevantTGFDs(Change change,DataVertex v)
    {
        for (String type:v.getTypes())
            if(relaventTGFDs.containsKey(type))
                change.addTGFD(relaventTGFDs.get(type));
    }

    /**
     * Extracts all the types being used in a TGFD from from X->Y dependency and the graph pattern
     * For each type, add the TGFD name to the HashMap so we know
     * what TGFDs are affected if a an entity of a specific type had a change
     * @param tgfd input TGFD
     */
    private void extractValidTypesFromTGFD(TGFD tgfd)
    {
        for (Literal x:tgfd.getDependency().getX()) {
            if(x instanceof ConstantLiteral)
                addRelevantType(((ConstantLiteral) x).getVertexType(),tgfd.getName());
            else if(x instanceof VariableLiteral)
            {
                addRelevantType(((VariableLiteral) x).getVertexType_1(),tgfd.getName());
                addRelevantType(((VariableLiteral) x).getVertexType_2(),tgfd.getName());
            }
        }
        for (Literal y:tgfd.getDependency().getY()) {
            if(y instanceof ConstantLiteral)
                addRelevantType(((ConstantLiteral) y).getVertexType(),tgfd.getName());
            else if(y instanceof VariableLiteral)
            {
                addRelevantType(((VariableLiteral) y).getVertexType_1(),tgfd.getName());
                addRelevantType(((VariableLiteral) y).getVertexType_2(),tgfd.getName());
            }
        }
        for (Vertex v:tgfd.getPattern().getGraph().vertexSet()) {
            if(v instanceof PatternVertex)
                for (String type:v.getTypes())
                    addRelevantType(type,tgfd.getName());
        }
    }

    /**
     * This method adds the TGFD name to the HashSet of the input type
     * @param type input type
     * @param TGFDName input TGFD name
     */
    private void addRelevantType(String type, String TGFDName)
    {
        if(!relaventTGFDs.containsKey(type))
            relaventTGFDs.put(type,new HashSet <>());
        relaventTGFDs.get(type).add(TGFDName);
    }

}
