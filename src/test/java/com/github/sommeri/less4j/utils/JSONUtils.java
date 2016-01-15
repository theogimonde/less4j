package com.github.sommeri.less4j.utils;

import java.util.ArrayList;
import java.util.List;

import javax.json.JsonArray;
import javax.json.JsonObject;

public class JSONUtils {

  public static String getString(JsonObject object, String propertyName) {
    if (object.containsKey(propertyName))
      return object.getString(propertyName);

    return null;
  }

  public static List<String> getStringList(JsonObject object, String propertyName) {
    if (object.containsKey(propertyName))
      return toStringList(object.getJsonArray(propertyName));

    return null;
  }

  public static List<String> toStringList(JsonArray jsonArray) {
    List<String> result = new ArrayList<String>();
    for (int i = 0; i < jsonArray.size(); i++) {
      result.add(jsonArray.isNull(i) ? null : jsonArray.getString(i));
    }
    return result;
  }

}
