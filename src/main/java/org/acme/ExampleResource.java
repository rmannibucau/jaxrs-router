package org.acme;

import javax.json.bind.annotation.JsonbProperty;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@Path("example")
@Produces(MediaType.APPLICATION_JSON)
public class ExampleResource {

    @GET
    @Path("jsonb")
    public JsonbExample jsonb() {
        return new JsonbExample();
    }

    @GET
    @Path("jackson")
    public JacksonExample jackson() {
        return new JacksonExample();
    }

    @RegisterForReflection
    public static class JsonbExample {
        @JsonbProperty("bar")
        public String foo = "is bar in json";
    }

    @RegisterForReflection
    public static class JacksonExample {
        @JsonProperty("bar")
        public String foo = "is bar in json";
    }
}