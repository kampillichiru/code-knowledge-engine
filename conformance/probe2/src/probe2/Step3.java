package probe2;

import java.util.function.Predicate;

/** Constructs that live outside plain method bodies or in expression position: initialisers, lambdas, switch expressions. */
public class Step3 {
    static int counter;

    static {
        if (counter > 5) {
            counter = 5;
        }
    }

    {
        for (int i = 0; i < 2; i++) {
            counter += i;
        }
    }

    int mode = counter > 5 ? 1 : 2;

    Predicate<Integer> positive = v -> v > 0 && v < 100;

    Runnable reset = () -> {
        if (counter > 3) {
            counter = 0;
        }
    };

    int viaSwitch(int k) {
        int r = switch (k) {
            case 1 -> 10;
            case 2 -> {
                if (k > 0) {
                    yield 20;
                }
                yield 30;
            }
            default -> 0;
        };
        return r;
    }

    void nested(int k) {
        System.out.println(switch (k) {
            case 1 -> "a";
            default -> "b";
        });
    }

    int assignSwitch(int k) {
        int v;
        v = switch (k) {
            case 1 -> 1;
            default -> 2;
        };
        return v;
    }

    String guardStmt(Object o) {
        switch (o) {
            case Integer i when i > 1 -> {
                return "x";
            }
            case Integer i -> {
                return "y";
            }
            default -> {
                return "z";
            }
        }
    }
}
