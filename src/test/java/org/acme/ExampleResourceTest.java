package org.acme;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.CoreMatchers.is;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ExampleResourceTest {
    @ParameterizedTest
    @ValueSource(strings = {"jsonb", "jackson"})
    public void testHelloEndpoint(final String endpoint) {
        given()
          .when()
                .accept(JSON)
                .get("/example/" + endpoint)
          .then()
             .statusCode(200)
             .body(is("{\"bar\":\"is bar in json\"}"));
    }
}