package edu.timetable.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import edu.timetable.dao.DataStore;
import edu.timetable.dao.Database;
import edu.timetable.service.TimetableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the web API over real HTTP against an in-memory database loaded with the sample department. */
class WebServerTest {
    private HttpServer server;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        TimetableService service = new TimetableService(new DataStore(Database.inMemory("web-" + UUID.randomUUID())));
        service.replaceWithSampleData();
        server = WebServer.start(service, 0);
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return Http.JSON.readTree(r.body());
    }

    @Test
    void servesThePageAndTheSummary() throws Exception {
        HttpResponse<String> page = call("GET", "/", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>SlotWise</title>"));
        assertEquals(404, call("GET", "/../pom.xml", null).statusCode());

        JsonNode s = json(call("GET", "/api/summary", null));
        assertEquals("SlotWise", s.get("app").asText());
        assertEquals(126, s.get("counts").get("meetings").asInt());
        assertEquals(4, s.get("counts").get("labGroups").asInt());
    }

    @Test
    void crudValidatesAndExplainsRefusals() throws Exception {
        HttpResponse<String> created = call("POST", "/api/rooms", "{\"code\":\"LH-9\",\"building\":\"New\",\"capacity\":80,\"type\":\"LECTURE\"}");
        assertEquals(200, created.statusCode());
        int id = json(created).get("id").asInt();
        assertEquals(200, call("PUT", "/api/rooms/" + id, "{\"code\":\"LH-9\",\"building\":\"Old\",\"capacity\":90,\"type\":\"LAB\"}").statusCode());
        assertEquals(204, call("DELETE", "/api/rooms/" + id, null).statusCode());

        HttpResponse<String> bad = call("POST", "/api/rooms", "{\"code\":\"\",\"building\":\"X\",\"capacity\":10,\"type\":\"LECTURE\"}");
        assertEquals(400, bad.statusCode());
        assertEquals("Enter a room code.", json(bad).get("error").asText());

        HttpResponse<String> dup = call("POST", "/api/rooms", "{\"code\":\"LH-101\",\"building\":\"X\",\"capacity\":10,\"type\":\"LECTURE\"}");
        assertEquals(409, dup.statusCode());

        int cse1a = 0;
        for (JsonNode b : json(call("GET", "/api/batches", null))) if (b.get("name").asText().equals("CSE-1A")) cse1a = b.get("id").asInt();
        HttpResponse<String> refused = call("DELETE", "/api/batches/" + cse1a, null);
        assertEquals(409, refused.statusCode());
        assertTrue(json(refused).get("error").asText().contains("still has courses or lab groups"));
    }

    @Test
    void generatesAndShowsATimetable() throws Exception {
        assertEquals(200, call("POST", "/api/generate", "{\"iterations\":50000}").statusCode());
        JsonNode st = json(call("GET", "/api/generate", null));
        for (int i = 0; i < 300 && st.get("running").asBoolean(); i++) {
            Thread.sleep(100);
            st = json(call("GET", "/api/generate", null));
        }
        assertEquals("SUCCESS", st.get("outcome").asText(), st.toString());
        int run = st.get("runId").asInt();

        JsonNode grid = json(call("GET", "/api/runs/" + run + "/grid?view=batch", null));
        assertTrue(grid.get("status").get("valid").asBoolean());
        assertEquals(126, grid.get("status").get("placed").asInt());
        assertEquals(5, grid.get("rows").size());
        assertEquals(10, grid.get("subjects").size());

        HttpResponse<String> csv = call("GET", "/api/runs/" + run + "/csv", null);
        assertEquals(200, csv.statusCode());
        assertTrue(csv.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
        assertTrue(csv.body().startsWith("Batch,Day,Period,"));
        assertEquals(400, call("GET", "/api/runs/" + run + "/grid?view=nonsense", null).statusCode());
    }
}
