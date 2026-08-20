package example;

public class ExtractSuperclassCallers {
    public void use(ExtractSuperclassSamples s) {
        s.doIt();
        s.run("hello");
        int c = ExtractSuperclassSamples.COUNT;
        System.out.println(c);
    }
}
