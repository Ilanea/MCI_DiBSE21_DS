package mci.rest;

import java.util.*;
import java.net.InetAddress;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;

import org.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ChordNodeApp extends Application {

    private static ChordNode chordNodeInstance;
    final static int SERVER_PORT = 8888;
    final static String SERVER_URL = "http://localhost:" + SERVER_PORT;
    final static String API_ENDPOINT = "/api";
    static String CHORD_ADDRESS = null;
    static String CHORD_NODE = null;
    private static int FINGERTABLE_SIZE;
    private Set<Object> singletons = new HashSet<Object>();
    static Logger log = LoggerFactory.getLogger(ChordNodeApp.class);

    public ChordNodeApp()
    {
        singletons.add(chordNodeInstance);
    }

    @Override
    public Set<Object> getSingletons()
    {
        return singletons;
    }

    public static void main(String[] args) throws Exception {
        log.info("Starting ChordNodeApp");
        Map<String, String> env = System.getenv();

        FINGERTABLE_SIZE = Integer.parseInt(env.get("FINGERTABLE_SIZE"));
        if(FINGERTABLE_SIZE < 1) {
            log.error("FINGERTABLE_SIZE not set or invalid");
            System.exit(1);
        } else {
            log.info("FINGERTABLE_SIZE is set to {}", FINGERTABLE_SIZE);
            chordNodeInstance = new ChordNode(FINGERTABLE_SIZE);
        }


        // Starting Jetty Server in an extra Thread
        Thread serverThread = new Thread(() -> {
            try {
                startServer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.start();

        // Setting up Chord Node
        log.info("Setting up Chord Node");
        String chord_address = env.get("CHORD_ADDRESS");
        if (chord_address == null || chord_address.isEmpty()) {
            log.error("CHORD_ADDRESS not set");
            System.exit(1);
        } else {
            CHORD_ADDRESS = chord_address;
            log.info("CHORD_ADDRESS is set to {}", chord_address);
            InetAddress ip = InetAddress.getLocalHost();
            String hostAddress = ip.getHostAddress();
            String serverUrl = "http://" + hostAddress + ":" + SERVER_PORT + API_ENDPOINT;

            chordNodeInstance.setId(Integer.parseInt(chord_address));
            chordNodeInstance.setAddress(serverUrl);
        }

        String chord_node = env.get("CHORD_NODE");
        if (chord_node == null || chord_node.isEmpty()) {
            log.info("We seem so be the first node in this chord network");
            InetAddress ip = InetAddress.getLocalHost();
            String hostAddress = ip.getHostAddress();
            String serverUrl = "http://" + hostAddress + ":" + SERVER_PORT + API_ENDPOINT;

            chordNodeInstance.setSuccessor(serverUrl);
            chordNodeInstance.setPredecessorId(null);
            chordNodeInstance.setPredecessorAddress(null);
            chordNodeInstance.initializeFirstFingerTable();
        } else {
            log.info("CHORD_NODE is set to {}", chord_node);
            CHORD_NODE = chord_node + API_ENDPOINT;

            // We are not the first node, so we contact the bootstrap node to introduce us to the network
            bootstrapClient(CHORD_NODE);
        }

        // Start Periodic Tasks for updating Finger Table and Predecessor
        chordNodeInstance.schedulePeriodicTasks();

    }

    private static void startServer() throws Exception {

        Server server = new Server(SERVER_PORT);

        final ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        // Set up RESTEasy servlet
        // Only works with Jetty version < 11
        final ServletHolder restEasyServlet = new ServletHolder("restEasyServlet", new HttpServletDispatcher());
        restEasyServlet.setInitParameter("resteasy.servlet.mapping.prefix", API_ENDPOINT);
        restEasyServlet.setInitParameter("javax.ws.rs.Application", ChordNodeApp.class.getName());
        context.addServlet(restEasyServlet, API_ENDPOINT + "/*");

        server.setHandler(context);

        try {
            server.start();
            server.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void bootstrapClient (String CHORD_BOOTSTRAP) {

        /*

        Creates a Client object to the bootstrap node and calls the joinChordNetwork method.
        The method returns the Successor and Predecessor of the joining node.
        The Successor and Predecessor are then set in the ChordNode object.
        The Finger Table is then initialized with the Successor and Predecessor. (Maybe errors in this step because we don't have a Finger Table yet)

        We notify our Successor that we are the new Predecessor.
        We notify our Predecessor that we are the new Successor.

         */

        if(CHORD_BOOTSTRAP == null || CHORD_BOOTSTRAP.isEmpty()) {
            log.error("CHORD_BOOTSTRAP not set");
            System.exit(1);
        }

        // Create default Client to get BOOTSTRAP Information
        Client client = ClientBuilder.newBuilder().build();
        WebTarget target = client.target(CHORD_BOOTSTRAP);
        ResteasyWebTarget rtarget = (ResteasyWebTarget)target;
        ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

        // Chord Info
        /*log.info("Calling getChordNode on {}", CHORD_BOOTSTRAP);
        String networkInfo = node.getChordNode();
        log.info("Answer from getChordNode: {}", networkInfo);*/

        chordNodeInstance.initializeFirstFingerTable();

        // Create Join URL
        String joinUrl = CHORD_BOOTSTRAP + "/node/join";

        // Join
        log.info("Calling joinChordNetwork on {}", joinUrl);
        String successorPredecessorTableJSON = node.joinChordNetwork(chordNodeInstance.getId(),chordNodeInstance.getAddress());
        log.info("Got Successor and Predecessor of joining Node from Bootstrapnode: {}", successorPredecessorTableJSON);

        // Deserialize JSON
        JSONObject json = new JSONObject(successorPredecessorTableJSON);
        String successorAddress = json.getString("successorAddress");

        // Set the successor and predecessor in the chord node instance
        chordNodeInstance.setPredecessorId(null);
        chordNodeInstance.setSuccessor(successorAddress);
        chordNodeInstance.setPredecessorAddress(null);

        // Notify my successor of his new predecessor
        updatePredeccessorOnSuccesor();

        // Initializing the finger table
        chordNodeInstance.initializeFingerTable();

    }

    // Notify my successor of his new predecessor (me)
    private static void updatePredeccessorOnSuccesor() {
        // Create Client for Successor
        Client client = ClientBuilder.newBuilder().build();
        WebTarget target = client.target(chordNodeInstance.getSuccessorAddress());
        ResteasyWebTarget rtarget = (ResteasyWebTarget)target;
        ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

        String updatePredecessorOnSuccessor = node.updatePredecessorQuery(chordNodeInstance.getId(), chordNodeInstance.getAddress());
        log.info("Calling updatePredecessor on Successor: {}", updatePredecessorOnSuccessor);
    }

}
