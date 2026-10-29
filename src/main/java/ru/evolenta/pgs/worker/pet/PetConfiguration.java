package ru.evolenta.pgs.worker.pet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ru.evolenta.pgs.worker.trade.core.Data;

import java.io.*;
import java.util.*;

final class PetConfiguration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> PROFILE =
            Data.immutableMap(
                    PetData.map(
                            PetData.map(read("config.json").get("profiles"))
                                    .get("Мой питомец")));
    private static final Map<String, Object> DICTIONARIES =
            Data.immutableMap(read("pet-dictionaries.json"));

    static Map<String, Object> read(String resource) {
        // Jackson 2.18.6 readValue(InputStream, TypeReference); no polymorphic/default typing.
        try (InputStream input =
                PetConfiguration.class.getResourceAsStream("/pet/" + resource)) {
            if (input == null)
                throw new IllegalStateException("Missing pet configuration " + resource);
            return JSON.readValue(input, new TypeReference<>() {});
        } catch (IOException error) {
            throw new IllegalStateException("Invalid pet configuration " + resource, error);
        }
    }

    Map<String, Object> profile() {
        return PROFILE;
    }

    List<Map<String, Object>> fields() {
        return PetData.maps(PROFILE.get("fields"));
    }

    List<Map<String, Object>> groups(String sheet) {
        return PetData.maps(PetData.map(PROFILE.get("groups")).get(sheet));
    }

    Map<String, Object> dictionary(String name) {
        return PetData.map(DICTIONARIES.get(name));
    }

    Set<String> ruleMethods() {
        var methods = new LinkedHashSet<String>();
        for (var field : fields())
            for (var rule : PetData.maps(field.get("rules")))
                methods.add(PetData.text(rule.get("method")));
        return Set.copyOf(methods);
    }

    int groupCount() {
        return PetData.map(PROFILE.get("groups")).values().stream()
                .mapToInt(value -> PetData.list(value).size())
                .sum();
    }
}
