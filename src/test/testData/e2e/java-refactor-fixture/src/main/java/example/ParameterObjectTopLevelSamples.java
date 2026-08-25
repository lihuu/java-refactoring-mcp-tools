package example;

import java.util.List;

public class ParameterObjectTopLevelSamples {
    public void createInvoice(String customer, String currency, int dueDays, boolean preview) {
        System.out.println(customer + ":" + currency + ":" + dueDays + ":" + preview);
        if (preview) {
            System.out.println("preview for " + customer);
        }
    }

    public <T> void handleGeneric(T payload, String suffix) {
        suffix = suffix.trim();
        System.out.println(payload.toString() + suffix);
    }

    public void handleList(List<String> items, String prefix) {
        prefix = prefix.trim();
        System.out.println(prefix + items.size());
    }
}
