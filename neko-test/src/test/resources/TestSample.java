/**
 * Sample Java program used for obfuscation testing.
 * Compiled to a JAR and then obfuscated to verify correctness.
 */
public class TestSample {
    private static final String SECRET = "Hello, NekoObfuscator!";
    private static final int MAGIC = 42;

    public static void main(String[] args) {
        TestSample sample = new TestSample();
        System.out.println(sample.greet("World"));
        System.out.println("Fibonacci(10) = " + sample.fibonacci(10));
        System.out.println("Sum = " + sample.sumArray(new int[]{1, 2, 3, 4, 5}));
        System.out.println("Secret: " + SECRET);
        System.out.println("Magic: " + MAGIC);
        sample.controlFlowDemo(5);
        sample.exceptionDemo();
        System.out.println("ALL TESTS PASSED");
    }

    public String greet(String name) {
        return "Hello, " + name + "!";
    }

    public int fibonacci(int n) {
        if (n <= 1) return n;
        int a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            int temp = a + b;
            a = b;
            b = temp;
        }
        return b;
    }

    public int sumArray(int[] arr) {
        int sum = 0;
        for (int val : arr) {
            sum += val;
        }
        return sum;
    }

    public void controlFlowDemo(int x) {
        String result;
        if (x > 10) {
            result = "big";
        } else if (x > 5) {
            result = "medium";
        } else if (x > 0) {
            result = "small";
        } else {
            result = "zero or negative";
        }
        System.out.println("Control flow: " + result);

        // Switch
        switch (x % 3) {
            case 0: System.out.println("Divisible by 3"); break;
            case 1: System.out.println("Remainder 1"); break;
            case 2: System.out.println("Remainder 2"); break;
        }
    }

    public void exceptionDemo() {
        try {
            int result = divide(10, 2);
            System.out.println("10 / 2 = " + result);
        } catch (ArithmeticException e) {
            System.out.println("Division error: " + e.getMessage());
        }

        try {
            divide(1, 0);
        } catch (ArithmeticException e) {
            System.out.println("Caught expected: " + e.getMessage());
        }
    }

    private int divide(int a, int b) {
        if (b == 0) throw new ArithmeticException("divide by zero");
        return a / b;
    }
}
