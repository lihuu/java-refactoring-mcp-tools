package example;

public class ExtractInterfaceCallers {
    public void use(ExtractInterfaceSamples s) {
        s.doIt();
        s.run("hello");
        int c = ExtractInterfaceSamples.COUNT;
        System.out.println(c);
    }
}
