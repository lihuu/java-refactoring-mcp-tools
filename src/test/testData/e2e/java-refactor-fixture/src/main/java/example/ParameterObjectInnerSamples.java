package example;

public class ParameterObjectInnerSamples {
    public void createInvoice(String customer, String currency, int dueDays) {
        System.out.println(customer + currency + dueDays);
        String assigned = currency;
        assigned = assigned.trim();
        System.out.println(assigned);
        System.out.println("inner preview");
    }
}
