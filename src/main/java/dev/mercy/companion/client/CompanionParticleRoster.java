package dev.mercy.companion.client;

import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Only the current player's server-confirmed roster can authorize a visual marker. */
final class CompanionParticleRoster {
    private record Entry(String dimension,boolean loaded) {}
    private Map<UUID,Entry> entries=Map.of();

    void accept(String json) {
        Map<UUID,Entry> next=new LinkedHashMap<>();
        try {
            var root=JsonParser.parseString(json).getAsJsonObject();
            if(root.has("healthy")&&!root.get("healthy").getAsBoolean()){clear();return;}
            for(var value:root.getAsJsonArray("slots")) {
                if(next.size()>=7)break;
                try {
                    var item=value.getAsJsonObject();
                    if(!"active".equals(item.get("status").getAsString())
                            || !item.get("deployed").getAsBoolean())continue;
                    double health=item.get("health").getAsDouble();
                    if(!Double.isFinite(health)||health<=0)continue;
                    UUID id=UUID.fromString(item.get("uuid").getAsString());
                    String dimension=item.get("dimension").getAsString();
                    if(dimension.isBlank())continue;
                    next.put(id,new Entry(dimension,item.has("loaded")&&item.get("loaded").getAsBoolean()));
                } catch(RuntimeException malformedEntry) {
                    // A malformed or incomplete entry never identifies an entity for rendering.
                }
            }
            entries=Map.copyOf(next);
        } catch(RuntimeException malformedRoster) {clear();}
    }

    boolean hasDeployed() {return !entries.isEmpty();}
    boolean shouldMark(UUID id,String dimension) {
        Entry entry=entries.get(id);
        return entry!=null&&entry.loaded()&&entry.dimension().equals(dimension);
    }
    void clear() {entries=Map.of();}
}
