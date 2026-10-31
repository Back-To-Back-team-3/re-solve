class Solution {
    public int solution(int a, int b) {
        // b - b 는 항상 0 → ArithmeticException
        return a / (b - b);
    }
}
