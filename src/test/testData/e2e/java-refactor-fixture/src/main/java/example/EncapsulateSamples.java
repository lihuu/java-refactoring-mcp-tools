package example;

public class EncapsulateSamples {
    int amount;
    String status;

    public EncapsulateSamples(int amount, String status) {
        this.amount = amount;
        this.status = status;
    }

    // Used to verify useAccessorsWhenAccessible=false vs true
    public int internalUse() {
        return amount + status.length();
    }
}
