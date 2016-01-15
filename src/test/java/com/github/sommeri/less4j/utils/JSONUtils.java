package com.github.sommeri.less4j.utils;

import java.util.ArrayList;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class JSONUtils {

  public static String getString(JSONObject object, String propertyName) {
    if (object.containsKey(propertyName))
      return (String) object.get(propertyName);

    return null;
  }

  public static List<String> getStringList(JSONObject object, String propertyName) {
    if (object.containsKey(propertyName))
      return toStringList((JSONArray) object.get(propertyName));

    return null;
  }

  public static List<String> toStringList(JSONArray jsonArray) {
    List<String> result = new ArrayList<String>();
    for (int i = 0; i < jsonArray.size(); i++) {
      result.add((String) jsonArray.get(i));
    }
    return result;
  }

}
