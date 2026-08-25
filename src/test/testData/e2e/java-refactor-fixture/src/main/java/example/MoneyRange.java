package example;

public class MoneyRange {
    private final String currency;
    private final int dueDays;

    public MoneyRange(String currency, int dueDays) {
        this.currency = currency;
        this.dueDays = dueDays;
    }

    public String getCurrency() { return currency; }
    public int getDueDays() { return dueDays; }
}
