package ru.evolenta.pgs.worker.trade;

import com.fasterxml.jackson.databind.JsonNode;

final class TradeResources {
    static final JsonNode IMPORT = TradeData.resource("decoded/8529550e6704b020c0b8723f5998f60deb474b9c26da4022eb3abadc7125eb10.json");
    static final JsonNode MAPPINGS = TradeData.resource("decoded/cc507e251a75e6ddc85d69d307a870f67ff11e51cf0fa2f47e665444aa5cf963.json");
    static final JsonNode SINGLE = TradeData.resource("decoded/ba4d00f24e002d8539b849c146ea22b6b266c50794096194c48f4e52ee026389.json");
    private TradeResources() {}
    static JsonNode profile(String name) {
        JsonNode config = IMPORT.path("profiles").path(name);
        if (!config.isObject()) throw new IllegalArgumentException("Неподдерживаемый вид данных: " + name);
        return config;
    }
}
