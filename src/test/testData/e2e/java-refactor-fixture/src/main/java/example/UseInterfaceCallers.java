package example;

public class UseInterfaceCallers {
    private Report field = new Report();

    public String describe(Report parameter) {
        Report local = parameter;
        boolean isReport = local instanceof Report;
        return local.label() + field.label() + isReport;
    }

    public Report produce() {
        return new Report();
    }
}
