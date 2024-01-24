package mci.rest;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Context;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.servlet.http.HttpServletRequest;

import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;

import org.json.JSONArray;
import org.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


@Path("/node")
public class ChordNode {
    private static int FINGERTABLE_SIZE;
    private Integer id;
    private String address;
    private FingerTable fingerTable;
    private Integer predecessorId;
    private String predecessorAddress;
    private boolean stabilizing = false;
    static Logger log = LoggerFactory.getLogger(ChordNode.class);

    public ChordNode(Integer fingertableSize) {
        this.FINGERTABLE_SIZE = fingertableSize;
        this.fingerTable = new FingerTable(FINGERTABLE_SIZE);
    }


    /*
     *
     * Getter and Setter
     *
     */

    public void setId(Integer id) {
        this.id = id;
    }
    public Integer getId() {
        return this.id;
    }
    public void setAddress(String address) {
        this.address = address;
    }
    public String getAddress() {
        return this.address;
    }
    public void setFingerTable(FingerTable fingerTable) {
        this.fingerTable = fingerTable;
    }
    public FingerTable getFingerTable() {
        return this.fingerTable;
    }
    public void setPredecessorId(Integer predecessor) {
        this.predecessorId = predecessor;
    }
    public Integer getPredecessorId() {
        return this.predecessorId;
    }
    public void setPredecessorAddress(String predecessorAddress) {
        this.predecessorAddress = predecessorAddress;
    }
    public String getPredecessorAddress() {
        return this.predecessorAddress;
    }
    private final Lock joinLock = new ReentrantLock();

    /*
     *
     * Fingertable
     *
     */

    public void initializeFirstFingerTable() {
        for (int i = 0; i < FINGERTABLE_SIZE; i++) {
            int start = (this.id + (1 << i)) % (1 << FINGERTABLE_SIZE);
            int end = (this.id + (1 << (i + 1))) % (1 << FINGERTABLE_SIZE) - 1;
            Finger finger = new Finger(start, this.address);
            finger.setInterval(start,end);
            this.fingerTable.setFinger(i, finger);
        }
    }

    public synchronized void initializeFingerTable() {
        log.info("Initializing finger table");
        for (int i = 0; i < FINGERTABLE_SIZE; i++) {
            int start = (this.id + (1 << i)) % (1 << FINGERTABLE_SIZE);
            int end = (this.id + (1 << (i + 1))) % (1 << FINGERTABLE_SIZE) - 1;

            JSONObject successor = new JSONObject(findSuccessor(start));
            String successorUrl = successor.getString("successorAddress");

            log.info("Initializing finger " + i + " with start: " + start + ", successor: " + successorUrl);

            Finger finger = new Finger(start, successorUrl);
            finger.setInterval(start,end);
            this.fingerTable.setFinger(i, finger);
        }
    }

    public void setSuccessor(String successorAddress) {
        int start = (this.id + (1 << 0)) % (1 << FINGERTABLE_SIZE);
        int end = (this.id + (1 << 1)) % (1 << FINGERTABLE_SIZE) - 1;
        Finger finger = new Finger(start, successorAddress);
        finger.setInterval(start,end);
        this.fingerTable.setFinger(0, finger);
    }

    public String getSuccessorAddress() {
        return this.fingerTable.getFinger(0).getNode();
    }

    public int getSuccessorId() {
        String successorAddress = this.fingerTable.getFinger(0).getNode();

        return chordId(successorAddress);
    }

    private boolean isBetween(int targetId, int startId, int endId, boolean inclusiveStart, boolean inclusiveEnd) {
        if (startId < endId) {
            // Normal interval
            if (inclusiveStart && inclusiveEnd) {
                return targetId >= startId && targetId <= endId;
            } else if (inclusiveStart) {
                return targetId >= startId && targetId < endId;
            } else if (inclusiveEnd) {
                return targetId > startId && targetId <= endId;
            } else {
                return targetId > startId && targetId < endId;
            }
        } else if (startId > endId) {
            // Interval wraps around the ring
            if (inclusiveStart && inclusiveEnd) {
                return targetId >= startId || targetId <= endId;
            } else if (inclusiveStart) {
                return targetId >= startId || targetId < endId;
            } else if (inclusiveEnd) {
                return targetId > startId || targetId <= endId;
            } else {
                return targetId > startId || targetId < endId;
            }
        } else {
            // startId == endId
            if (inclusiveStart && inclusiveEnd) {
                return targetId == startId;
            } else {
                return false;
            }
        }
    }


    private String findSuccessor(int nodeIdToFind) {
        log.info("Finding successor for node ID: " + nodeIdToFind);
        log.info("Current node ID: " + this.id + ", Successor ID: " + this.getSuccessorId());

        // If the provided node ID is between this node and its successor, return our successor
        if (isBetween(nodeIdToFind, this.id, this.getSuccessorId(), false, true)) {
            log.info("Our current Successor is the successor of the Node ID: " + nodeIdToFind);
            if (isNodeReachable(this.getSuccessorAddress())) {
                return createSuccessorResponse(this.getSuccessorId(), this.getSuccessorAddress());
            } else {
                return createSuccessorResponse(this.id, this.address);
            }
        }

        // Check finger table for a closer node
        else {
            // Find the closest preceding node from the finger table
            String closestPrecedingNodeAddress = findClosestPrecedingSuccessorNode(nodeIdToFind);

            if (!closestPrecedingNodeAddress.equals(this.address)) {
                // Forward the query to the closest preceding node
                return forwardFindSuccessorQuery(nodeIdToFind, closestPrecedingNodeAddress);
            } else {
                // If the closest preceding node is this node, return this node's successor
                return createSuccessorResponse(this.getSuccessorId(), this.getSuccessorAddress());
            }
        }
    }

    private String findClosestPrecedingSuccessorNode(int nodeIdToFind) {
        for (int i = FINGERTABLE_SIZE - 1; i >= 0; i--) {
            int fingerId = chordId(this.fingerTable.getFinger(i).getNode());
            if (isNodeReachable(this.fingerTable.getFinger(i).getNode())) {
                if (isBetween(fingerId, this.id, nodeIdToFind, false, false)) {
                    return this.fingerTable.getFinger(i).getNode();
                }
            }
        }
        return this.address;
    }

    private String createSuccessorResponse(int successorId, String successorAddress) {
        JSONObject response = new JSONObject();
        response.put("successorId", successorId);
        response.put("successorAddress", successorAddress);
        return response.toString();
    }

    private String forwardFindSuccessorQuery(int nodeIdToFind, String nodeAddress) {
        log.info("Forwarding findSuccessor query to find Node " + nodeIdToFind + " to " + nodeAddress);
        Client client = ClientBuilder.newBuilder().build();
        WebTarget target = client.target(nodeAddress);
        ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
        ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

        String successor = node.findSuccessorQuery(nodeIdToFind);

        return successor;
    }

    private String findPredecessor(int nodeIdToFind) {
        log.info("Finding predecessor for node ID: " + nodeIdToFind);
        log.info("Current node ID: " + this.id + ", Predecessor ID: " + this.predecessorId);

        // Check if the node itself is the predecessor
        if (nodeIdToFind == this.getSuccessorId()) {
            log.info("We are the predecessor, returning own address");
            return createPredecessorResponse(this.id, this.address);
        }

        // Check if we are the node to find
        else if (nodeIdToFind == this.id) {
            log.info("We are the node to find, returning own predecessor address");
            if(isNodeReachable(this.predecessorAddress)){
                return createPredecessorResponse(this.predecessorId, this.predecessorAddress);
            } else {
                return createPredecessorResponse(this.id, this.address);
            }
        }

        // Check finger table for a closer node
        else {
            // Find the closest preceding node from the finger table
            String closestPrecedingNodeAddress = findClosestPrecedingPredecessorNode(nodeIdToFind);

            if (!closestPrecedingNodeAddress.equals(this.address)) {
                // Forward the query to the closest preceding node
                return forwardFindPredecessorQuery(nodeIdToFind, closestPrecedingNodeAddress);
            } else {
                // If the closest preceding node is this node, return this node
                return createPredecessorResponse(this.id, this.address);
            }
        }
    }

    private String findClosestPrecedingPredecessorNode(int nodeIdToFind) {
        for (int i = FINGERTABLE_SIZE - 1; i >= 0; i--) {
            int fingerId = chordId(this.fingerTable.getFinger(i).getNode());
            if (isNodeReachable(this.fingerTable.getFinger(i).getNode())) {
                if (isBetween(nodeIdToFind, fingerId, this.id, false, true)) {
                    return this.fingerTable.getFinger(i).getNode();
                }
            }
        }
        return this.address;
    }

    private String createPredecessorResponse(int predecessorId, String predecessorAddress) {
        JSONObject response = new JSONObject();
        response.put("predecessorId", predecessorId);
        response.put("predecessorAddress", predecessorAddress);
        return response.toString();
    }

    private String forwardFindPredecessorQuery(int nodeIdToFind, String nodeAddress) {
        log.info("Forwarding findPredecessor query to find Node " + nodeIdToFind + " to " + nodeAddress);
        Client client = ClientBuilder.newBuilder().build();
        WebTarget target = client.target(nodeAddress);
        ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
        ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

        String predecessor = node.findPredecessorQuery(nodeIdToFind);

        return predecessor;
    }

    public String newNodeToFingerTable(int newNodeId, String newNodeAddress) {
        boolean updatedFinger = false;

        for (int i = 0; i < FINGERTABLE_SIZE; i++) {
            int start = (this.id + (1 << i)) % (1 << FINGERTABLE_SIZE);
            int end = (this.id + (1 << (i + 1))) % (1 << FINGERTABLE_SIZE) - 1;

            Finger currentFinger = this.fingerTable.getFinger(i);
            int currentFingerNodeId = chordId(currentFinger.getNode());

            //log.info("New node ID: " + newNodeId + ", current finger ID: " + currentFingerNodeId + ", start: " + start);
            //log.info("Is new node closer to start than current finger? " + isCloser(newNodeId, currentFingerNodeId, start));

            // Update finger if new node is closer to the start than the current finger's node
            if (isCloser(newNodeId, currentFingerNodeId, start)) {
                Finger finger = new Finger(start, newNodeAddress);
                finger.setInterval(start,end);
                this.fingerTable.setFinger(i, finger);
                log.warn("Updated finger table entry " + i + " with start: " + start + ", new node: " + newNodeAddress);
                updatedFinger = true;
            }
        }

        return updatedFinger ? "Finger table updated" : "Finger table not updated";
    }

    private boolean isCloser(int nodeId, int comparedNodeId, int targetId) {
        int totalNodes = 1 << FINGERTABLE_SIZE;

        int distanceToTarget = (nodeId - targetId + totalNodes) % totalNodes;
        int comparedDistanceToTarget = (comparedNodeId - targetId + totalNodes) % totalNodes;

        return distanceToTarget < comparedDistanceToTarget;
    }

    private int chordId(String nodeAddress) {
        Client client = ClientBuilder.newBuilder().build();
        WebTarget target = client.target(nodeAddress);
        ResteasyWebTarget rtarget = (ResteasyWebTarget)target;
        ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

        String nodeInfo = node.getChordNodeId();
        JSONObject json = new JSONObject(nodeInfo);

        return json.getInt("id");
    }

    /*
    *
    * Message Handling
    *
    *
     */

    public String sendMessageToNode(int destinationId, String message) {
        if (this.id.equals(destinationId)) {
            log.info("Message received: " + message);

            return "Message received";
        } else {
            // Forward the message to the appropriate node
            String nextNodeAddress = findNextNodeForMessage(destinationId);
            forwardMessage(message, destinationId, nextNodeAddress);

            return "Message forwarded";
        }
    }

    private String findNextNodeForMessage(int destinationId) {
        for (int i = FINGERTABLE_SIZE - 1; i >= 0; i--) {
            int fingerId = chordId(this.fingerTable.getFinger(i).getNode());
            if (fingerId != this.id && isBetween(fingerId, this.id, destinationId, false, false)) {
                return this.fingerTable.getFinger(i).getNode();
            }
        }
        return this.getSuccessorAddress();
    }

    private void forwardMessage(String message, int destinationId, String nextNodeAddress) {
        Client client = ClientBuilder.newBuilder().build();
        WebTarget target = client.target(nextNodeAddress);
        ResteasyWebTarget rtarget = (ResteasyWebTarget)target;
        ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

        node.sendMessageToNode(destinationId, message);
    }

    /*
    *
    *  Schedules Tasks
    *
    *
     */

    public synchronized void stabilize() {
        this.stabilizing = true;

        log.info("Running stabilize task on Node " + this.id);
        // Check if successor is reachable
        boolean isReachable = isNodeReachable(this.getSuccessorAddress());
        if (!isReachable) {
            log.error("Successor is not reachable. Need to find next available successor.");

            // We need to take the node out of our finger table and update the successor to the next node in the chord network
            // We also need to notify other nodes about this
            for (int i = 1; i < FINGERTABLE_SIZE; i++) {
                Finger finger = this.fingerTable.getFinger(i);
                if (isNodeReachable(finger.getNode())) {
                    // Update the successor with the first reachable node
                    this.setSuccessor(finger.getNode());
                    log.info("Updated successor to " + finger.getNode());

                    log.info("Reinitializing finger table...");
                    this.initializeFingerTable();

                    break;
                }
            }
        }

        // Retrieve successor's current predecessor
        if(isNodeReachable(this.getSuccessorAddress())) {
            JSONObject json = new JSONObject(getSuccessorsPredecessorAddress());

            // Extracting the successor and predecessor IDs
            int predecessorId = json.getInt("predecessorId");
            String predecessorAddress = json.getString("predecessorAddress");

            // If the successor's predecessor is not this node
            if (predecessorAddress != null && !predecessorAddress.equals(this.address)) {
                if (isBetween(predecessorId, this.id, this.getSuccessorId(), false, true)) {
                    // Update successor if a closer node is found
                    this.setSuccessor(predecessorAddress);

                    log.info("Updated successor to " + this.getSuccessorAddress() + " with ID " + this.getSuccessorId());
                    this.newNodeToFingerTable(predecessorId, predecessorAddress);

                }
            }
        }

        // Check if my predecessor is still reachable
        if(isNodeReachable(this.getPredecessorAddress())) {
            this.notifyPredecessor();
        } else {
            // if not, ask my successor to find a new predecessor for me
            log.info("Predecessor is not reachable. Asking successor to find new predecessor for me.");
            Client client = ClientBuilder.newBuilder().build();
            WebTarget target = client.target(this.getSuccessorAddress());
            ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
            ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

            String askSuccessorForPredecessor = node.findPredecessorQuery(this.id);
            JSONObject json = new JSONObject(askSuccessorForPredecessor);

            String predecessorAddress = json.getString("predecessorAddress");
            int predecessorId = json.getInt("predecessorId");

            if (predecessorAddress != null && !predecessorAddress.equals(this.address)) {
                this.setPredecessorAddress(predecessorAddress);
                this.setPredecessorId(predecessorId);

                this.newNodeToFingerTable(predecessorId, predecessorAddress);

                log.info("Updated predecessor to " + this.getPredecessorAddress() + " with ID " + this.getPredecessorId());

                this.notifyPredecessor();
            }
        }

        log.info("Reinitializing finger table...");
        this.initializeFingerTable();

        // Notify the successor & predecessor about this node
        if(isNodeReachable(this.getSuccessorAddress())){
            this.notifySuccessor();
        }

        log.info("Stabilization task completed on Node " + this.id);
        this.stabilizing = false;
    }

    private void fixFingers(){
        log.info("Running fix fingers task on Node " + this.id);

        for (int i = 1; i < FINGERTABLE_SIZE; i++) {
            Finger finger = this.fingerTable.getFinger(i);
            if (!isNodeReachable(finger.getNode())) {
                log.warn("Finger " + i + " is not reachable. Need to find next available finger.");

                // We need to take the node out of our finger table and find the next node in the chord network
                if(i < FINGERTABLE_SIZE){
                    Finger newFinger = this.fingerTable.getFinger(i+1);
                    if(isNodeReachable(newFinger.getNode())){
                        this.fingerTable.setFinger(i, newFinger);
                        log.info("Updated finger " + i + " to " + newFinger.getNode());
                    }
                } else {
                    // If we are at the last finger we should ask another node to find the next finger for us
                    Client client = ClientBuilder.newBuilder().build();
                    WebTarget target = client.target(this.getSuccessorAddress());
                    ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
                    ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

                    String askSuccessorForFinger = node.findSuccessorQuery(finger.getStart());
                    JSONObject json = new JSONObject(askSuccessorForFinger);

                    String fingerAddress = json.getString("successorAddress");

                    if (fingerAddress != null && !fingerAddress.equals(this.address)) {
                        int start = (this.id + (1 << i)) % (1 << FINGERTABLE_SIZE);
                        int end = (this.id + (1 << (i + 1))) % (1 << FINGERTABLE_SIZE) - 1;
                        Finger newFinger = new Finger(start, fingerAddress);
                        newFinger.setInterval(start, end);

                        this.fingerTable.setFinger(i, newFinger);
                        log.info("Updated finger " + i + " to " + fingerAddress);
                    }
                }
            }
        }

        log.info("Reinitializing finger table...");
        this.initializeFingerTable();

        log.info("Fix fingers task completed on Node " + this.id);
    }

    private String getSuccessorsPredecessorAddress() {
        Client client = ClientBuilder.newBuilder().build();
        WebTarget target = client.target(this.getSuccessorAddress());
        ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
        ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

        String successorPredecessorAddress = node.getPredecessor();

        return successorPredecessorAddress;
    }

    private void notifySuccessor() {
        try {
            Client client = ClientBuilder.newBuilder().build();
            WebTarget target = client.target(this.getSuccessorAddress());
            ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
            ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

            node.updatePredecessorQuery(this.id, this.address);
        } catch (Exception e) {
            log.error("Error during notifySuccessor: " + e.getMessage());
        }
    }

    private void notifyPredecessor() {
        try {
            Client client = ClientBuilder.newBuilder().build();
            WebTarget target = client.target(this.predecessorAddress);
            ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
            ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

            node.updateSuccessorQuery(this.id, this.address);
        } catch (Exception e) {
            log.error("Error during notifyPredecessor: " + e.getMessage());
        }
    }

    private boolean isNodeReachable(String nodeAddress) {
        Client client = ClientBuilder.newBuilder().build();
        WebTarget target = client.target(nodeAddress);
        ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
        ChordNodeInterface node = rtarget.proxy(ChordNodeInterface.class);

        try {
            if(node.ping().equals("OK")) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            log.error("Error during isNodeReachable: " + e.getMessage());
            return false;
        } finally {
            client.close();
        }
    }

    // Method to schedule periodic tasks
    public void schedulePeriodicTasks() {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(() -> {
            if(!this.stabilizing){
                stabilize();
                fixFingers();
            }
        }, 10, 30, TimeUnit.SECONDS);
    }


    /*
     *
     * API Endpoints
     *
     */

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    public String getChordNode(@Context HttpServletRequest req) {
        log.info("Received request from " + req.getRemoteAddr() + " to get chord node info");

        JSONArray chord = new JSONArray();
        chord.put("Chord Node Info");

        JSONObject info = new JSONObject();
        info.put("id", this.id);
        info.put("address", this.address);
        info.put("successor", this.getSuccessorId());
        info.put("successorAddress", this.getSuccessorAddress());
        info.put("predecessor", this.predecessorId);
        info.put("predecessorAddress", this.predecessorAddress);

        chord.put(info);

        if(this.fingerTable != null) {
            JSONArray fingerTable = new JSONArray();
            for (int i = 0; i < FINGERTABLE_SIZE; i++) {
                JSONObject finger = new JSONObject();
                finger.put("start", this.fingerTable.getFinger(i).getStart());
                finger.put("interval", this.fingerTable.getFinger(i).getInterval());
                finger.put("address", this.fingerTable.getFinger(i).getNode());
                fingerTable.put(finger);
            }
            chord.put(fingerTable);
        }

        return chord.toString();
    }

    @POST
    @Path("/join")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public synchronized String joinChordNetwork(@Context HttpServletRequest req, @QueryParam("id") int id, @QueryParam("address") String address) {

        joinLock.lock();
        try {
            log.info("Received request from " + req.getRemoteAddr() + " to join the chord network with id " + id + " and address " + address);

            // add the new node to my fingertable
            //this.newNodeToFingerTable(id, address);

            // Find the successor & predecessor for the new node
            JSONObject successorForNewNode = new JSONObject(this.findSuccessor(id));
            JSONObject predecessorForNewNode = new JSONObject(this.findPredecessor(id));

            // Create a JSON response with successor and predecessor info
            int successorId = successorForNewNode.getInt("successorId");
            int predecessorId = predecessorForNewNode.getInt("predecessorId");
            String successorAddress = successorForNewNode.getString("successorAddress");
            String predecessorAddress = predecessorForNewNode.getString("predecessorAddress");

            JSONObject response = new JSONObject();
            response.put("successorId", successorId);
            response.put("successorAddress", successorAddress);
            response.put("predecessorId", predecessorId);
            response.put("predecessorAddress", predecessorAddress);

            // add the new node to my fingertable
            this.newNodeToFingerTable(id, address);

            return response.toString();
        } finally {
            joinLock.unlock();
        }
    }

    @GET
    @Path("/id")
    @Produces(MediaType.APPLICATION_JSON)
    public String getChordNodeId() {
        JSONObject response = new JSONObject();
        response.put("id", this.id);

        return response.toString();
    }

    @GET
    @Path("/address")
    @Produces(MediaType.APPLICATION_JSON)
    public String getChordNodeAddress() {
        JSONObject response = new JSONObject();
        response.put("address", this.address);

        return response.toString();
    }

    @GET
    @Path("/find-successor/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public String findSuccessorQuery(@PathParam("id") int id) {
        log.info("Received request to find successor of node:" + id);

        String successor = findSuccessor(id);

        log.info("Returning successor " + successor);
        return successor;
    }

    @POST
    @Path("/update-successor")
    @Consumes(MediaType.APPLICATION_JSON)
    public String updateSuccessorQuery(@QueryParam("id") int successorId, @QueryParam("address") String successorAddress) {
        joinLock.lock();
        try {
            log.info("Received request to update successor to Node " + successorId + " with Address " + successorAddress);

            this.setSuccessor(successorAddress);

            this.newNodeToFingerTable(successorId, successorAddress);

            return "Successor updated";
        } finally {
            joinLock.unlock();
        }
    }

    @GET
    @Path("/find-predecessor/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public String findPredecessorQuery(@PathParam("id") int id) {
        log.info("Received request to find predecessor of node: " + id);

        String predecessor = findPredecessor(id);

        log.info("Returning predecessor " + predecessor);
        return predecessor;
    }

    @GET
    @Path("/get-predecessor")
    @Produces(MediaType.APPLICATION_JSON)
    public String getPredecessorQuery() {
        log.info("Received request to get predecessor");

        JSONObject response = new JSONObject();
        response.put("predecessorId", this.predecessorId);
        response.put("predecessorAddress", this.predecessorAddress);

        return response.toString();
    }

    @POST
    @Path("/update-predecessor")
    @Consumes(MediaType.APPLICATION_JSON)
    public String updatePredecessorQuery(@QueryParam("id") int predecessorId, @QueryParam("address") String predecessorAddress) {
        joinLock.lock();
        try {
            log.info("Received request to update predecessor to Node " + predecessorId + " with Address " + predecessorAddress);

            this.setPredecessorAddress(predecessorAddress);
            this.setPredecessorId(predecessorId);

            this.newNodeToFingerTable(predecessorId, predecessorAddress);

            return "Predecessor updated";
        } finally {
            joinLock.unlock();
        }
    }

    @POST
    @Path("/update-finger-table")
    @Consumes(MediaType.APPLICATION_JSON)
    public String updateFingerTable(@QueryParam("id") int newNodeId, @QueryParam("address") String newNodeAddress){
        log.info("Received request to update finger table with new node " + newNodeId + " with address " + newNodeAddress);

        // Add a new node to the finger table
        return this.newNodeToFingerTable(newNodeId, newNodeAddress);
    }

    @GET
    @Path("/health-check")
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        return "OK";
    }

    @GET
    @Path("/leave")
    @Produces(MediaType.TEXT_PLAIN)
    public void leave() {
        log.info("Received request to leave the network");

        // Create Client for Successor
        Client successorClient = ClientBuilder.newBuilder().build();
        WebTarget successorTarget = successorClient.target(this.getSuccessorAddress());
        ResteasyWebTarget rSuccessorTarget = (ResteasyWebTarget)successorTarget;
        ChordNodeInterface successor = rSuccessorTarget.proxy(ChordNodeInterface.class);


        // Create Client for Predecessor
        Client predecessorClient = ClientBuilder.newBuilder().build();
        WebTarget predecessorTarget = predecessorClient.target(this.getPredecessorAddress());
        ResteasyWebTarget rPredecessorTarget = (ResteasyWebTarget)predecessorTarget;
        ChordNodeInterface predecessor = rPredecessorTarget.proxy(ChordNodeInterface.class);

        if(isNodeReachable(this.getSuccessorAddress()) && isNodeReachable(this.getPredecessorAddress())) {
            successor.updatePredecessorQuery(this.getPredecessorId(), this.getPredecessorAddress());
            predecessor.updateSuccessorQuery(this.getSuccessorId(), this.getSuccessorAddress());

            System.exit(0);
        } else {
            log.error("Successor or Predecessor is not reachable, still leaving.");
            System.exit(0);
        }
    }

    @GET
    @Path("/send-message/{destinationId}/{message}")
    @Consumes(MediaType.APPLICATION_JSON)
    public String sendMessage(@PathParam("destinationId") int destinationId, @PathParam("message") String message) {
        log.info("Received request to send message to node " + destinationId + " with message " + message);

        String response = this.sendMessageToNode(destinationId, message);

        return response;
    }

}
