package mci.rest;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Path("/testservice")
public interface TestService {

    @GET
    @Path("/helloworld")
    @Produces(MediaType.TEXT_PLAIN)
    String getHelloMessage();
}
