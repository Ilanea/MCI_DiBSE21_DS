package mci.rest;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Path("/node")
public interface ChordNodeInterface {

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    String getChordNode();

    @GET
    @Path("/id")
    @Produces(MediaType.APPLICATION_JSON)
    String getChordNodeId();

    @GET
    @Path("/address")
    @Produces(MediaType.APPLICATION_JSON)
    String getChordNodeIdAddress();

    @POST
    @Path("/join")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    String joinChordNetwork(@QueryParam("id") int id, @QueryParam("address") String newNode);

    @GET
    @Path("/find-successor/{idToFind}")
    @Produces(MediaType.APPLICATION_JSON)
    String findSuccessorQuery(@PathParam("idToFind") int idToFind);

    @POST
    @Path("/update-successor")
    @Consumes(MediaType.APPLICATION_JSON)
    String updateSuccessorQuery(@QueryParam("id") int successorId, @QueryParam("address") String successorAddress);

    @GET
    @Path("/find-predecessor/{idToFind}")
    @Produces(MediaType.APPLICATION_JSON)
    String findPredecessorQuery(@PathParam("idToFind") int idToFind);

    @POST
    @Path("/update-predecessor")
    @Consumes(MediaType.APPLICATION_JSON)
    String updatePredecessorQuery(@QueryParam("id") int predecessorId, @QueryParam("address") String predecessorAddress);

    @POST
    @Path("/update-finger-table")
    @Consumes(MediaType.APPLICATION_JSON)
    String updateFingerTable(@QueryParam("id") int newNodeId, @QueryParam("address") String newNodeAddress);

    @GET
    @Path("/get-predecessor")
    @Produces(MediaType.APPLICATION_JSON)
    String getPredecessor();

    @GET
    @Path("/health-check")
    @Produces(MediaType.TEXT_PLAIN)
    String ping();

    @GET
    @Path("/leave")
    @Produces(MediaType.TEXT_PLAIN)
    void leave();

    @POST
    @Path("/send-message")
    @Consumes(MediaType.APPLICATION_JSON)
    String sendMessageToNode(@QueryParam("destinationId") int destinationId, @QueryParam("message") String message);

    @PUT
    @Path("/data/add")
    @Consumes(MediaType.APPLICATION_JSON)
    String addDataQuery(@QueryParam("value") String value);

    @DELETE
    @Path("/data/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    String removeDataQuery(@QueryParam("key") Integer key);

    @GET
    @Path("/data/get")
    @Consumes(MediaType.APPLICATION_JSON)
    String getDataQuery(@QueryParam("key") Integer key);
}
