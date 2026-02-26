package com.rlxo.huemc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal client for talking to a local Hue bridge.
 */
public class HueBridgeClient {

    private final HttpClient httpClient;
    private final Logger logger;

    public HueBridgeClient(Logger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public RegistrationResult registerUser(String bridgeIp, String deviceType) {
        String payload = "{\"devicetype\":\"" + sanitize(deviceType) + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + bridgeIp + "/api"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseRegistrationResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Hue registerUser interrupted");
            return new RegistrationResult(false, false, null, "Interrupted");
        } catch (IOException e) {
            logger.warning("Hue registerUser failed: " + e.getMessage());
            return new RegistrationResult(false, false, null, "Network error");
        } catch (Exception e) {
            logger.warning("Hue registerUser unexpected error: " + e.getMessage());
            return new RegistrationResult(false, false, null, "Unexpected error");
        }
    }

    public ActionResult setGroupPower(String bridgeIp, String username, boolean on) {
        String payload = "{\"on\":" + on + "}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + bridgeIp + "/api/" + username + "/groups/0/action"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseActionResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Hue setGroupPower interrupted");
            return new ActionResult(false, "Interrupted");
        } catch (IOException e) {
            logger.warning("Hue setGroupPower failed: " + e.getMessage());
            return new ActionResult(false, "Network error");
        } catch (Exception e) {
            logger.warning("Hue setGroupPower unexpected error: " + e.getMessage());
            return new ActionResult(false, "Unexpected error");
        }
    }

    private RegistrationResult parseRegistrationResponse(String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonArray()) {
            return new RegistrationResult(false, false, null, "Bridge responded with invalid data");
        }

        JsonArray array = parsed.getAsJsonArray();
        if (array.isEmpty()) {
            return new RegistrationResult(false, false, null, "Bridge responded with no data");
        }

        JsonObject first = array.get(0).getAsJsonObject();
        if (first.has("success")) {
            JsonObject success = first.getAsJsonObject("success");
            String username = success.has("username") ? success.get("username").getAsString() : "";
            return new RegistrationResult(true, false, username, "");
        }

        if (first.has("error")) {
            JsonObject error = first.getAsJsonObject("error");
            int type = error.has("type") ? error.get("type").getAsInt() : -1;
            String description = error.has("description") ? error.get("description").getAsString() : "Unknown error";
            boolean linkButton = type == 101;
            return new RegistrationResult(false, linkButton, null, description);
        }

        return new RegistrationResult(false, false, null, "Bridge response not understood");
    }

    private ActionResult parseActionResponse(String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonArray()) {
            return new ActionResult(false, "Bridge responded with invalid data");
        }

        JsonArray array = parsed.getAsJsonArray();
        if (array.isEmpty()) {
            return new ActionResult(false, "Bridge responded with no data");
        }

        JsonObject first = array.get(0).getAsJsonObject();
        if (first.has("success")) {
            return new ActionResult(true, "");
        }

        if (first.has("error")) {
            JsonObject error = first.getAsJsonObject("error");
            String description = error.has("description") ? error.get("description").getAsString() : "Unknown error";
            return new ActionResult(false, description);
        }

        return new ActionResult(false, "Bridge response not understood");
    }

    private String sanitize(String input) {
        return input.replace("\"", "");
    }

    public Map<String, HueLight> listLights(String bridgeIp, String username) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + bridgeIp + "/api/" + username + "/lights"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseLightsResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Hue listLights interrupted");
            return Map.of();
        } catch (IOException e) {
            logger.warning("Hue listLights failed: " + e.getMessage());
            return Map.of();
        } catch (Exception e) {
            logger.warning("Hue listLights unexpected error: " + e.getMessage());
            return Map.of();
        }
    }

    public ActionResult setLightPower(String bridgeIp, String username, String lightId, boolean on) {
        JsonObject obj = new JsonObject();
        obj.addProperty("on", on);
        return setLightState(bridgeIp, username, lightId, obj);
    }

    public ActionResult setLightState(String bridgeIp, String username, String lightId, JsonObject state) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + bridgeIp + "/api/" + username + "/lights/" + lightId + "/state"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(state.toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseActionResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Hue setLightState interrupted");
            return new ActionResult(false, "Interrupted");
        } catch (IOException e) {
            logger.warning("Hue setLightState failed: " + e.getMessage());
            return new ActionResult(false, "Network error");
        } catch (Exception e) {
            logger.warning("Hue setLightState unexpected error: " + e.getMessage());
            return new ActionResult(false, "Unexpected error");
        }
    }

    private Map<String, HueLight> parseLightsResponse(String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            return Map.of();
        }

        Map<String, HueLight> lights = new HashMap<>();
        JsonObject obj = parsed.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String id = entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject lightObj = entry.getValue().getAsJsonObject();
            String name = lightObj.has("name") ? lightObj.get("name").getAsString() : id;
            JsonObject state = lightObj.has("state") && lightObj.get("state").isJsonObject()
                    ? lightObj.get("state").getAsJsonObject()
                    : new JsonObject();
            lights.put(id, new HueLight(id, name, state.deepCopy()));
        }
        return lights;
    }

    public record HueLight(String id, String name, JsonObject state) { }

    public record RegistrationResult(boolean success, boolean linkButtonNotPressed, String username, String message) { }

    public record ActionResult(boolean success, String message) { }
}
