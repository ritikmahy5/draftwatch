package dev.draftwatch.scoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@code --name value} flags of one subcommand; an unknown or valueless flag is an error. */
final class Flags {
  private final Map<String, List<String>> values;

  private Flags(Map<String, List<String>> values) {
    this.values = values;
  }

  /**
   * Parses {@code args}. Flags in {@code booleans} take no value.
   *
   * @throws IllegalArgumentException naming the offending flag
   */
  static Flags parse(List<String> args, Set<String> known, Set<String> booleans) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    for (int i = 0; i < args.size(); i++) {
      String flag = args.get(i);
      if (!known.contains(flag) && !booleans.contains(flag)) {
        throw new IllegalArgumentException("unknown flag: " + flag);
      }
      if (booleans.contains(flag)) {
        values.computeIfAbsent(flag, k -> new ArrayList<>());
        continue;
      }
      if (i + 1 >= args.size()) {
        throw new IllegalArgumentException(flag + " needs a value");
      }
      values.computeIfAbsent(flag, k -> new ArrayList<>()).add(args.get(++i));
    }
    return new Flags(values);
  }

  String required(String flag) {
    return optional(flag).orElseThrow(() -> new IllegalArgumentException("missing " + flag));
  }

  Optional<String> optional(String flag) {
    List<String> v = values.get(flag);
    if (v == null || v.isEmpty()) {
      return Optional.empty();
    }
    if (v.size() > 1) {
      throw new IllegalArgumentException(flag + " given more than once");
    }
    return Optional.of(v.get(0));
  }

  List<String> all(String flag) {
    return List.copyOf(values.getOrDefault(flag, List.of()));
  }

  boolean present(String flag) {
    return values.containsKey(flag);
  }
}
