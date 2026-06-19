package demo;
public class MethodScope {
    void run() {
        int <caret>userCount = 0;
        userCount++;
        System.out.println(userCount);
    }
}
