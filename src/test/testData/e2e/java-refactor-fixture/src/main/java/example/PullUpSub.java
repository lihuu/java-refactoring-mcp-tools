package example;

public class PullUpSub extends PullUpBase {
    public void handle(String s) {
        System.out.println(s);
    }

    public static final int COUNT = 1;

    private void help() {
        System.out.println("help");
    }
}
