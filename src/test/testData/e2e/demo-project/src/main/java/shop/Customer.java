package shop;

public class Customer {
    public String name;
    public String email;
    public int loyaltyPoints;

    public Customer(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public void addPoints(int points) {
        loyaltyPoints += points;
    }
}
