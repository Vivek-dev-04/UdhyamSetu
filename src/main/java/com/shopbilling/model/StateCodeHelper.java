package com.shopbilling.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility mapping standard Indian 2-digit GST state codes to state names.
 */
public final class StateCodeHelper {

    private static final Map<String, String> CODE_TO_STATE;
    private static final Map<String, String> STATE_TO_CODE;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("01", "Jammu and Kashmir");
        map.put("02", "Himachal Pradesh");
        map.put("03", "Punjab");
        map.put("04", "Chandigarh");
        map.put("05", "Uttarakhand");
        map.put("06", "Haryana");
        map.put("07", "Delhi");
        map.put("08", "Rajasthan");
        map.put("09", "Uttar Pradesh");
        map.put("10", "Bihar");
        map.put("11", "Sikkim");
        map.put("12", "Arunachal Pradesh");
        map.put("13", "Nagaland");
        map.put("14", "Manipur");
        map.put("15", "Mizoram");
        map.put("16", "Tripura");
        map.put("17", "Meghalaya");
        map.put("18", "Assam");
        map.put("19", "West Bengal");
        map.put("20", "Jharkhand");
        map.put("21", "Odisha");
        map.put("22", "Chhattisgarh");
        map.put("23", "Madhya Pradesh");
        map.put("24", "Gujarat");
        map.put("26", "Dadra and Nagar Haveli and Daman and Diu");
        map.put("27", "Maharashtra");
        map.put("29", "Karnataka");
        map.put("30", "Goa");
        map.put("31", "Lakshadweep");
        map.put("32", "Kerala");
        map.put("33", "Tamil Nadu");
        map.put("34", "Puducherry");
        map.put("35", "Andaman and Nicobar Islands");
        map.put("36", "Telangana");
        map.put("37", "Andhra Pradesh");
        map.put("38", "Ladakh");

        CODE_TO_STATE = Collections.unmodifiableMap(map);

        Map<String, String> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            reverse.put(e.getValue().toLowerCase(), e.getKey());
        }
        STATE_TO_CODE = Collections.unmodifiableMap(reverse);
    }

    private StateCodeHelper() {}

    public static String getStateName(String code) {
        if (code == null) return "Rajasthan";
        String clean = code.trim();
        return CODE_TO_STATE.getOrDefault(clean, "Rajasthan");
    }

    public static String getStateCode(String stateName) {
        if (stateName == null) return "08";
        String clean = stateName.trim().toLowerCase();
        return STATE_TO_CODE.getOrDefault(clean, "08");
    }

    public static Map<String, String> getAllStates() {
        return CODE_TO_STATE;
    }
}
