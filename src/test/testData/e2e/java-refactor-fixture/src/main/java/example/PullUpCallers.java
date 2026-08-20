package example;

public class PullUpCallers {
    public void use(PullUpSub s) {
        s.handle("x");
        int c = PullUpSub.COUNT;
        System.out.println(c);
    }
}
