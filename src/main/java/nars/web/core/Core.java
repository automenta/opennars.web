/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package nars.web.core;

import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.KeyIndexableGraph;
import com.tinkerpop.blueprints.Parameter;
import com.tinkerpop.blueprints.TransactionalGraph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.gremlin.java.GremlinPipeline;
import com.tinkerpop.pipes.PipeFunction;
import com.tinkerpop.pipes.branch.LoopPipe;
import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static java.util.stream.StreamSupport.stream;
import javafx.application.Platform;
import jnetention.p2p.Listener;
import jnetention.p2p.Network;
import org.apache.commons.math3.stat.Frequency;
import org.vertx.java.core.json.impl.Json;

/**
 * Unifies DB & P2P features
 */
public class Core extends EventEmitter {

    private final static String Session_MYSELF = "myself";

    static final Pattern primitiveRegEx = Pattern.compile("/^(class|property|boolean|text|html|integer|real|url|object|spacepoint|timepoint|timerange|sketch|markdown|image|tagcloud|chat)$/");

    public static boolean isPrimitive(String s) {
        return primitiveRegEx.matcher(s).matches();
    }

    public Network net;

    //https://github.com/thinkaurelius/titan/blob/c958ad2a2bafd305a33655347fef17138ee75088/titan-test/src/main/java/com/thinkaurelius/titan/graphdb/TitanIndexTest.java
    //public final TitanGraph graph;
    TransactionalGraph graph;

    public Map<String, Object> getObject(final Vertex v) {
        return getObject(v, Collections.EMPTY_SET);
    }

    public Map<String, Object> getObject(final Vertex v, Set<String> propertyExclude) {

        Map<String, Object> r = new HashMap();

        for (String s : v.getPropertyKeys()) {
            if (!propertyExclude.contains(s)) {
                r.put(s, v.getProperty(s));
            }
        }

        Iterable<Edge> outs = v.getEdges(Direction.OUT);
        Map<String, List<String>> outMap = new HashMap();
        for (Edge e : outs) {
            String edge = e.getLabel();
            String uri = e.getVertex(Direction.IN).getProperty("uri");
            List<String> uris = outMap.get(edge);
            if (uris == null) {
                uris = new ArrayList();
                outMap.put(edge, uris);
            }
            uris.add(uri);
        }
        if (outMap.size() > 0) {
            r.put("out", outMap);
        }

        Iterable<Edge> ins = v.getEdges(Direction.IN);
        Map<String, List<String>> inMap = new HashMap();
        for (Edge e : ins) {
            String edge = e.getLabel();
            String uri = e.getVertex(Direction.OUT).getProperty("uri");
            List<String> uris = inMap.get(edge);
            if (uris == null) {
                uris = new ArrayList();
                inMap.put(edge, uris);
            }
            uris.add(uri);
        }
        if (inMap.size() > 0) {
            r.put("in", inMap);
        }

        return r;
    }

    public Map<String, Object> getObject(final String objId) {

        Vertex v = vertex(objId, false);

        if (v == null) {
            Map<String, Object> h = new HashMap();
            h.put("error", objId + " not found");
            return h;
        }
        Map<String, Object> r = getObject(v);
        graph.commit();

        return r;
    }

    public void cache(Vertex v, String type) {
        v.setProperty(type + "_modifiedAt", System.currentTimeMillis());
    }

    public boolean cached(Vertex v, String type) {
        long maxCachedTime = 7 * 24 * 60 * 60 * 1000; //1 week
        long now = System.currentTimeMillis();
        Object l = v.getProperty(type + "_modifiedAt");
        if (l == null) {
            return false;
        }

        long then = (long) l;
        if (now - then < maxCachedTime) {
            return true;
        }
        return false;
    }

    public Map<String, Object> vertexProperties(Vertex v) {
        Map<String, Object> m = new HashMap();
        for (String s : v.getPropertyKeys()) {
            m.put(s, v.getProperty(s));
        }
        return m;
    }

    public void commit() {
        graph.commit();
    }


    public static class SaveEvent {

        public final NObject object;

        public SaveEvent(NObject object) {
            this.object = object;
        }
    }

    public static class NetworkUpdateEvent {

    }

    final Map<String, NProperty> property = new HashMap();
    final Map<String, NClass> nclass = new HashMap();

    private NObject myself;

    public Core(TransactionalGraph db) {

        this.graph = db;

        if (!((KeyIndexableGraph) graph).getIndexedKeys(Vertex.class).contains("uri")) {
            ((KeyIndexableGraph) graph).createKeyIndex("uri", Vertex.class, new Parameter("type", "UNIQUE"));
        }

//                
//                TitanManagement mgmt = graph.getManagementSystem();
//        if (!mgmt.containsGraphIndex("uri")) {
//            PropertyKey name = mgmt.makePropertyKey("uri").dataType(String.class).cardinality(Cardinality.SINGLE).make();
//            TitanGraphIndex namei = mgmt.buildIndex("uri",Vertex.class).addKey(name).unique().buildCompositeIndex();
//            mgmt.commit();
//        }        
    }

    public void addRDF(Model rdf, String topic) {
        topic = u(topic);

        StmtIterator l = rdf.listStatements();
        while (l.hasNext()) {
            Statement s = l.next();
            Resource subj = s.getSubject();
            RDFNode obj = s.getObject();

            Property p = s.getPredicate();
            String ps = u(p.toString());

            //certain properties
            switch (ps) {
                case "purl.org/dc/terms/subject":
                case "www.w3.org/1999/02/22-rdf-syntax-ns#type":
                case "www.w3.org/2000/01/rdf-schema#label":
                    break;
                /*case "http://www.w3.org/2002/07/owl#sameAs":
                 if (!subj.getURI().equals(topic))
                 continue;
                 break;*/
                /*case "http://dbpedia.org/ontology/division":
                 case "http://dbpedia.org/ontology/subdivision":
                 case "http://dbpedia.org/ontology/subdivisio":
                 if (!subj.getURI().equals(topic))
                 continue;
                 break;*/
                default:
                    //System.out.println("  -: " + ps + " " + obj.toString() + " (ignored)");
                    continue;
            }

            String usub = u(subj.getURI());
            Vertex sv = vertex(usub, true);
            if (obj instanceof Resource) {
                String ovu = u(((Resource) obj).getURI());
                Vertex ov = vertex(ovu, true);
                uniqueEdge(sv, ov, p.toString());
            } else if (obj instanceof Literal) {
                //TODO support other literal types
                String str = ((Literal) obj).getString();
                sv.setProperty("rdf", str);
            }

        }
        graph.commit();

    }

    /**
     * removes any existing edges between the two vertices, then adds it
     */
    public Edge uniqueEdge(Vertex from, Vertex to, String predicate) {
        Iterable<Edge> existing = from.getEdges(Direction.OUT, predicate);
        for (Edge e : existing) {
            if (e.getVertex(Direction.IN).equals(to)) {
                //System.out.println(predicate + " existing edge: " + e + " " + e.getLabel() + " " + e.getProperty("uri"));

                //TODO set any updated properties
                return e;
            }
        }
        return addEdge(from, to, predicate);
    }

    public Edge addEdge(Vertex sv, Vertex ov, String predicate) {
        System.out.println("  +: " + sv.toString() + " " + predicate + " " + ov.toString());
        Edge e = graph.addEdge(null, sv, ov, predicate);
        return e;
    }

    /**
     * normalize a URL by removing http://
     */
    public static String u(final String url) {
        if (url.startsWith("http://")) {
            return url.substring(7);
        }
        return url;
    }

    public void printGraph() {
        for (Vertex v : (Iterable<Vertex>) graph.getVertices()) {
            System.out.println(v.toString() + " " + v.getProperty("uri"));
        }
        for (Edge e : (Iterable<Edge>) graph.getEdges()) {
            System.out.println(e.toString() + " " + e.getLabel() + " " + e.getPropertyKeys());
        }
    }

    public Vertex vertex(String uri, boolean createIfNonExist) {
        for (Object v : graph.getVertices("uri", uri)) {
            if (v != null) {
                return (Vertex) v;
            }
        }
        if (createIfNonExist) {
            Vertex v = graph.addVertex(uri);
            v.setProperty("uri", uri);
            return v;
        }
        return null;
    }

    public void addObjects(Iterable<NObject> N) {
        for (NObject n : N) {
            Vertex v = vertex(n.id, true);
            if (n instanceof NProperty) {
                NProperty np = (NProperty) n;
                property.put(np.id, np);
                //TODO add to graph?
            } else if (n instanceof NClass) {
                NClass nc = (NClass) n;
                nclass.put(nc.id, nc);
                for (String s : nc.getExtend()) {
                    Vertex p = vertex(s, true);
                    uniqueEdge(v, p, "is");
                }
            } else if (n instanceof NObject) {
                NObject no = (NObject)n;
                for (String t : no.getTags()) {
                    Vertex p = vertex(t, true);
                    uniqueEdge(v, p, "is");
                }
            }
        }
        graph.commit();
    }

    public void addObject(NObject... N) {
        addObjects(Arrays.asList(N));
    }

    /**
     * eigenvector centrality
     */
    public Map<Vertex, Number> centrality(final int iterations, Vertex start) {
        Map<Vertex, Number> map = new HashMap();

        new GremlinPipeline<Vertex, Vertex>(graph.getVertices()).start(start).as("x").both().groupCount(map).loop("x",
                new PipeFunction<LoopPipe.LoopBundle<Vertex>, Boolean>() {

                    int c = 0;

                    @Override
                    public Boolean compute(LoopPipe.LoopBundle<Vertex> a) {
                        return (c++ < iterations);
                    }
                }).iterate();

        return map;

        //out(label);
        /*
         m = [:]; c = 0;
         g.V.as('x').out.groupCount(m).loop('x'){c++ < 1000}
         m.sort{-it.value}
         */
    }

    public Core online(int listenPort) throws IOException, UnknownHostException, SocketException, InterruptedException {
        net = new Network(listenPort);
        net.listen("obj.", new Listener() {
            @Override
            public void handleMessage(String topic, String message) {
                System.err.println("recv: " + message);
            }
        });

        //net.getConfiguration().setBehindFirewall(true);                
        System.out.println("Server started listening to ");
        System.out.println("Accessible to outside networks at ");

        return this;
    }

    protected void broadcastSelf() {
        if (myself != null) {
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    broadcast(myself);
                }
            });
        }
    }

    public void connect(String host, int port) throws UnknownHostException {
        net.connect(host, port);
    }

    /*public Core offline() {
        
     return this;
     }*/
    public Iterable<NObject> netValues() {
        return Collections.EMPTY_LIST;
//        
//        //dht.storageLayer().checkTimeout();
//        return Iterables.filter(Iterables.transform(dht.storageLayer().get().values(), 
//            new Function<Data,NObject>() {
//                @Override public NObject apply(final Data f) {
//                    try {
//                        final Object o = f.object();
//                        if (o instanceof NObject) {
//                            NObject n = (NObject)o;
//                            
//                            if (data.containsKey(n.id))
//                                return null;                                
//                            
//                            /*System.out.println("net value: " + f.object() + " " + f.object().getClass() + " " + data.containsKey(n.id));*/
//                            return n;
//                        }
//                        /*else {
//                            System.out.println("p: " + o + " " + o.getClass());
//                        }*/
//                        /*else if (o instanceof String) {
//                            Object p = dht.get(Number160.createHash((String)o));
//                            System.out.println("p: " + p + " " + p.getClass());
//                        }*/
//                        return null;
//                    } catch (Exception ex) {
//                        ex.printStackTrace();
//                        return null;
//                    }
//                }                
//        }), Predicates.notNull());        
    }

//    public Stream<NObject> objectStream() {
////        if (net!=null) {
////            //return Stream.concat(data.values().stream(), netValues());
////            return data.values().stream();
////        }
//        
//        return data.values().stream();
//    }
    public Stream<Vertex> objectStreamByTag(final String tagID) {
        Vertex v = vertex(tagID, false);
        return stream(v.getEdges(Direction.IN, "is").spliterator(), false).map(e -> e.getVertex(Direction.OUT));
    }

    public Stream<Vertex> objectStreamByAuthor(final String author) {
        Vertex v = vertex(author, false);
        if (v == null)
            return Stream.empty();
        return Stream.concat(Stream.of(v), stream(v.getEdges(Direction.OUT, "has").spliterator(), false).map(e -> e.getVertex(Direction.OUT)));
    }
    
//    public Stream<NObject> objectStreamByTagAndAuthor(final String tagID, final String author) {
//        return objectStream().filter(o -> (o.author == author && o.hasTag(tagID)));
//    }
//    
//    public Stream<NObject> objectStreamByTag(final Tag t) {
//        return objectStreamByTag(t.name());
//    }
//    public Stream<NObject> userStream() {        
//        return objectStreamByTag(Tag.User);
//    }
//    /** list all possible subjects, not just users*/
//    public List<NObject> getSubjects() {
//        return userStream().collect(Collectors.toList());
//    }
    public NObject newUser(String id) {
        NObject n = new NObject("Anonymous", id);
        n.author = n.id;
        n.add(Tag.User);
        n.add(Tag.Human);
        n.add("@", new SpacePoint(40, -80));
        addObject(n);
        return n;
    }

    /**
     * creates a new anonymous object, but doesn't publish it yet
     */
    public NObject newAnonymousObject(String name) {
        NObject n = new NObject(name);
        return n;
    }

    /**
     * creates a new object (with author = myself), but doesn't publish it yet
     */
    public NObject newObject(String name) {
        if (myself == null) {
            throw new RuntimeException("Unidentified; can not create new object");
        }

        NObject n = new NObject(name);
        n.author = myself.id;
        return n;
    }

//    public void become(NObject user) {
//        //System.out.println("Become: " + user);
//        myself = user;
//        session.put(Session_MYSELF, user.id);
//    }
    public void remove(String nobjectID) {
//        data.remove(nobjectID);
    }

    public void remove(NObject x) {
        remove(x.id);
    }

    /**
     * save nobject to database
     */
    public void save(NObject x) {
//        NObject removed = data.put(x.id, x);        
//        index(removed, x);
//        
//        //emit(SaveEvent.class, x);
    }

    /**
     * batch save nobject to database
     */
    public void save(Iterable<NObject> y) {
//        for (NObject x : y) {
//            NObject removed = data.put(x.id, x);
//            index(removed, x);
//        }            
//        //emit(SaveEvent.class, null);
    }

    public void broadcast(NObject x) {
        broadcast(x, false);
    }

    public synchronized void broadcast(NObject x, boolean block) {
        if (net != null) {
            System.err.println("broadcasting " + x);
            net.send("obj0", x.toStringDetailed());
//            try {
//                
//                    
//            }
//            catch (IOException e) {
//                System.err.println("publish: " + e);
//            }
        }
    }

    /**
     * save to database and publish in DHT
     */
    public void publish(NObject x, boolean block) {
        save(x);

        broadcast(x, block);

        //TODO save to geo-index
    }

    public void publish(NObject x) {
        publish(x, false);
    }

    /*
     public int getNetID() {
     if (net == null)
     return -1;
     return net.
     }
     */
    public NObject getMyself() {
        return myself;
    }

    protected void index(NObject previous, NObject o) {
        if (previous != null) {
            if (previous.isClass()) {

            }
        }

        if (o != null) {

            if ((o.isClass()) || (o.isProperty())) {

                for (Map.Entry<String, Object> e : o.value.entries()) {
                    String superclass = e.getKey();
                    if (superclass.equals("tag")) {
                        continue;
                    }

                    if (nclass.get(superclass) == null) {
                        save(new NClass(superclass));
                    }

                }

            }

        }

    }

    public static Frequency tokenBag(String x, int minLength, int maxTokenLength) {
        String[] tokens = tokenize(x);
        Frequency f = new Frequency();
        for (String t : tokens) {
            if (t == null) {
                continue;
            }
            if (t.length() < minLength) {
                continue;
            }
            if (t.length() > maxTokenLength) {
                continue;
            }
            t = t.toLowerCase();
            f.addValue(t);
        }
        return f;
    }

    public static String[] tokenize(String value) {
        String v = value.replaceAll(",", " \uFFEB ").
                replaceAll("\\.", " \uFFED").
                replaceAll("\\!", " \uFFED"). //TODO alternate char
                replaceAll("\\?", " \uFFED") //TODO alternate char
                ;
        return v.split(" ");
    }

    public Stream<NClass> classStreamRoots() {
        return nclass.values().stream().filter(n -> n.getExtend().isEmpty());
    }

    public String getOntologyJSON() {
        Map<String, Object> o = new HashMap();
        o.put("property", property.values());
        o.put("class", nclass.values());
        return Json.encode(o);
    }

}
