package shortener;

public final class Base62 {
    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Base62() {}

    public static String encode(long n) {
        if (n < 0) throw new IllegalArgumentException("negative");
        if (n == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            sb.append(ALPHABET.charAt((int) (n % 62)));
            n /= 62;
        }
        return sb.reverse().toString();
    }

    public static long decode(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("empty");
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = ALPHABET.indexOf(s.charAt(i));
            if (d < 0) throw new IllegalArgumentException("invalid char " + s.charAt(i));
            n = Math.addExact(Math.multiplyExact(n, 62), d);
        }
        return n;
    }
}
