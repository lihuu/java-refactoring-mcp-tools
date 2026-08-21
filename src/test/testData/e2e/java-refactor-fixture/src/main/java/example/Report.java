package example;

public class Report implements Printable {
    @Override
    public String label() {
        return "report";
    }

    public void onlyOnReport() {
        System.out.println("only on report");
    }
}
