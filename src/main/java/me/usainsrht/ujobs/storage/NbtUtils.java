package me.usainsrht.ujobs.storage;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal, self-contained NBT reader sufficient to locate and extract the `ujobs`
 * compound from a player .dat file. This avoids reflection on server internals
 * and supports all common primitive/list/array/compound tag types used by MC.
 */
public class NbtUtils {

    /**
     * Extracts a textual inner representation of the ujobs compound suitable for
     * feeding into PDCStorage.parseFromText(). Returns null if not found or on error.
     */
    public static String extractUjobsInner(File datFile) {
           try (FileInputStream fis = new FileInputStream(datFile);
               GZIPInputStream gis = new GZIPInputStream(fis);
               DataInputStream dis = new DataInputStream(gis)) {

            byte rootTag = dis.readByte();
            if (rootTag != 10) return null; // not a compound root
            short rootNameLen = dis.readShort();
            if (rootNameLen > 0) {
                byte[] rn = new byte[rootNameLen];
                dis.readFully(rn);
            }

            Map<String, Object> root = readCompound(dis);
            if (root == null) return null;

            // Search for keys that match ujobs directly, namespaced forms, or jobs_data inside ujobs
            Object found = findUjobsCompound(root);
            if (found instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ujobsMap = (Map<String, Object>) found;
                // Build textual inner: one entry per job like "ujobs:jobid:{level:1 exp:123.0}"
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Map.Entry<String, Object> e : ujobsMap.entrySet()) {
                    String jobKey = e.getKey();
                    // Normalize job key: strip leading 'ujobs:' if present
                    String normJobKey = jobKey.startsWith("ujobs:") ? jobKey.substring("ujobs:".length()) : jobKey;
                    Object v = e.getValue();
                    if (!(v instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> jobBody = (Map<String, Object>) v;
                    Integer level = null;
                    Double exp = null;
                    // Try level keys (both raw and namespaced)
                    if (jobBody.containsKey("level") || jobBody.containsKey("ujobs:level")) {
                        Object lv = jobBody.containsKey("level") ? jobBody.get("level") : jobBody.get("ujobs:level");
                        if (lv instanceof Number) level = ((Number) lv).intValue();
                        else {
                            try { level = Integer.parseInt(String.valueOf(lv)); } catch (Exception ignored) {}
                        }
                    }
                    // Try common exp keys and several heuristic alternatives
                    String[] expKeys = new String[] {"exp","xp","total_exp","totalExp","total-experience","experience","totalxp","total_xp","total"};
                    for (String k : expKeys) {
                        // check both raw and namespaced keys
                        String k1 = k;
                        String k2 = "ujobs:" + k;
                        if (jobBody.containsKey(k1) || jobBody.containsKey(k2)) {
                            Object exv = jobBody.containsKey(k1) ? jobBody.get(k1) : jobBody.get(k2);
                            if (exv instanceof Number) {
                                exp = ((Number) exv).doubleValue();
                                break;
                            } else {
                                try { exp = Double.parseDouble(String.valueOf(exv)); break; } catch (Exception ignored) {}
                            }
                        }
                    }
                    // Check nested maps (e.g., jobBody -> stats -> exp)
                    if (exp == null) {
                        for (Map.Entry<String, Object> kv : jobBody.entrySet()) {
                            if (kv.getValue() instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String,Object> nested = (Map<String,Object>) kv.getValue();
                                for (String k : expKeys) {
                                    String k1 = k;
                                    String k2 = "ujobs:" + k;
                                    if (nested.containsKey(k1) || nested.containsKey(k2)) {
                                        Object exv = nested.containsKey(k1) ? nested.get(k1) : nested.get(k2);
                                        if (exv instanceof Number) { exp = ((Number) exv).doubleValue(); break; }
                                        try { exp = Double.parseDouble(String.valueOf(exv)); break; } catch (Exception ignored) {}
                                    }
                                }
                                if (exp != null) break;
                            }
                        }
                    }

                    if (!first) sb.append(' ');
                    first = false;
                    // Prefix with ujobs: to match parseFromText's acceptance
                    sb.append("ujobs:").append(normJobKey).append(":{");
                    if (level != null) sb.append("level:").append(level);
                    if (exp != null) {
                        if (level != null) sb.append(' ');
                        sb.append("exp:").append(exp);
                    }
                    sb.append('}');
                }

                return sb.length() == 0 ? null : sb.toString();
            }

            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the raw ujobs compound map (if found) so callers can inspect keys/values.
     */
    public static Map<String,Object> extractUjobsMap(File datFile) {
        try (FileInputStream fis = new FileInputStream(datFile);
             GZIPInputStream gis = new GZIPInputStream(fis);
             DataInputStream dis = new DataInputStream(gis)) {

            byte rootTag = dis.readByte();
            if (rootTag != 10) return null;
            short rootNameLen = dis.readShort();
            if (rootNameLen > 0) {
                byte[] rn = new byte[rootNameLen];
                dis.readFully(rn);
            }

            Map<String, Object> root = readCompound(dis);
            if (root == null) return null;
            Object found = findUjobsCompound(root);
            if (found instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String,Object> uj = (Map<String,Object>) found;
                return uj;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static Object findUjobsCompound(Map<String, Object> map) {
        // direct key
        if (map.containsKey("ujobs")) return map.get("ujobs");
        // namespaced key e.g. "ujobs:jobs_data"
        for (String k : map.keySet()) {
            if (k != null && k.startsWith("ujobs")) return map.get(k);
        }
        // search recursively
        for (Object v : map.values()) {
            if (v instanceof Map) {
                Object found = findUjobsCompound((Map<String, Object>) v);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Map<String, Object> readCompound(DataInputStream dis) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            byte tag = dis.readByte();
            if (tag == 0) break;
            short nameLen = dis.readShort();
            byte[] nameBytes = new byte[nameLen];
            dis.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            Object value = readPayload(dis, tag);
            map.put(name, value);
        }
        return map;
    }

    private static Object readPayload(DataInputStream dis, byte tag) throws IOException {
        switch (tag) {
            case 1: return dis.readByte();
            case 2: return dis.readShort();
            case 3: return dis.readInt();
            case 4: return dis.readLong();
            case 5: return dis.readFloat();
            case 6: return dis.readDouble();
            case 7: {
                int len = dis.readInt();
                byte[] arr = new byte[len];
                dis.readFully(arr);
                return arr;
            }
            case 8: {
                short slen = dis.readShort();
                byte[] sbytes = new byte[slen];
                dis.readFully(sbytes);
                return new String(sbytes, StandardCharsets.UTF_8);
            }
            case 9: {
                byte childTag = dis.readByte();
                int len = dis.readInt();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(dis, childTag));
                }
                return list;
            }
            case 10: return readCompound(dis);
            case 11: {
                int len = dis.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readInt();
                return arr;
            }
            case 12: {
                int len = dis.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readLong();
                return arr;
            }
            default:
                throw new IOException("Unknown NBT tag: " + tag);
        }
    }
}
