package probe;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Probe {
    static final BigDecimal RATE = new BigDecimal("0.05");
    static int counter;
    static {
        counter = 10;
    }
    private final List<String> names = new ArrayList<>();
    int total = compute(5);

    enum Status { OPEN, ARCHIVED }

    record Pair(int a, int b) {}

    interface Rule {
        int apply(int x);
    }

    int implicitElse(int x) {
        if (x > 10) {
            x = x * 2;
        }
        return x;
    }

    int earlyReturn(int x) {
        if (x < 0) {
            return -1;
        }
        x = x + 5;
        return x;
    }

    void throwsArm(int x) {
        if (x == 0) {
            throw new IllegalArgumentException("zero");
        }
        total = total + x;
    }

    int loops(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i];
        }
        while (s > 100) {
            s = s - 10;
        }
        for (int v : a) {
            s = s + v;
        }
        do {
            s++;
        } while (s < 5);
        return s;
    }

    int tryCatch(String t) {
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return 0;
        } finally {
            counter++;
        }
    }

    String switchOld(int k) {
        switch (k) {
            case 1:
                return "one";
            case 2:
            case 3:
                return "few";
            default:
                return "many";
        }
    }

    String switchNew(Status s) {
        return switch (s) {
            case OPEN -> "o";
            case ARCHIVED -> "c";
        };
    }

    int switchYield(int k) {
        return switch (k) {
            case 1 -> 10;
            default -> {
                int t = k * 2;
                yield t;
            }
        };
    }

    String patternSwitch(Object o) {
        return switch (o) {
            case Integer i when i > 5 -> "big";
            case String s -> s;
            default -> "x";
        };
    }

    boolean instanceOfPattern(Object o) {
        if (o instanceof String s && s.length() > 3) {
            return true;
        }
        return false;
    }

    int ternary(int x) {
        return x > 0 ? x : -x;
    }

    int logical(int a, int b) {
        if (a > 0 && b > 0 || a < -5) {
            return 1;
        }
        return 0;
    }

    int lambda(List<Integer> xs) {
        return xs.stream()
                .filter(v -> v > 2)
                .map(v -> v * 2)
                .reduce(0, Integer::sum);
    }

    Rule anon() {
        return new Rule() {
            public int apply(int x) {
                if (x > 1) {
                    return x * 3;
                }
                return x;
            }
        };
    }

    int varargs(int... xs) {
        int s = 0;
        for (int x : xs) {
            s += x;
        }
        return s;
    }

    int callVarargs() {
        return varargs(1, 2, 3);
    }

    int collections() {
        Map<String, Integer> m = new HashMap<>();
        m.put("a", 1);
        int v = m.get("a");
        names.add("n");
        return v + names.size();
    }

    int nullCheck(String s) {
        if (s == null) {
            return -1;
        }
        return s.length();
    }

    int sync(Object lock) {
        synchronized (lock) {
            total = total + 1;
        }
        return total;
    }

    int labeled(int[][] g) {
        int c = 0;
        outer:
        for (int[] row : g) {
            for (int v : row) {
                if (v < 0) {
                    break outer;
                }
                if (v == 0) {
                    continue;
                }
                c = c + v;
            }
        }
        return c;
    }

    int assertAndCast(Object o) {
        assert o != null;
        int n = (Integer) o;
        return n;
    }

    int compute(int x) {
        return x * 2;
    }

    static int staticHelper(int a) {
        return a + counter;
    }

    class Inner {
        int v = 3;

        int get() {
            return v + total;
        }
    }
}
