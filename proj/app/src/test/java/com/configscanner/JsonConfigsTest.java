package com.configscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class JsonConfigsTest {

    @Test
    public void fullXrayJsonKeepsIpv6EndpointAndDocumentRemark() throws Exception {
        String json = "{\"remarks\":\"Test Config\",\"inbounds\":[],"
                + "\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"vmess\","
                + "\"settings\":{\"vnext\":[{\"address\":\"2001:db8::13\","
                + "\"port\":18901,\"users\":[{\"id\":"
                + "\"00000000-0000-4000-8000-000000000001\",\"alterId\":0,"
                + "\"security\":\"auto\"}]}]},\"streamSettings\":{"
                + "\"network\":\"ws\",\"security\":\"none\","
                + "\"wsSettings\":{\"path\":\"/geo-test\"}}},"
                + "{\"tag\":\"direct\",\"protocol\":\"freedom\","
                + "\"settings\":{}}]}";

        List<String> links = JsonConfigs.extract(json);
        assertEquals(1, links.size());

        ServerSpec server = ServerSpec.parse(links.get(0));
        assertNotNull(server);
        assertEquals("2001:db8::13", server.host);
        assertEquals(18901, server.port);
        assertEquals("Test Config", server.name);
        assertEquals("ws", server.network);
        assertEquals("/geo-test", server.path);

        JSONObject generated = new JSONObject(XrayConfig.buildFull(server, 11080));
        JSONArray outbounds = generated.getJSONArray("outbounds");
        JSONObject proxy = outbounds.getJSONObject(0);
        JSONObject firstServer = proxy.getJSONObject("settings")
                .getJSONArray("vnext").getJSONObject(0);
        assertEquals("2001:db8::13", firstServer.getString("address"));
        assertEquals(18901, firstServer.getInt("port"));
        assertTrue(generated.getJSONArray("inbounds").length() > 0);
    }
}
