class Solution {
    public boolean solution(String str) {
        // 문자열 앞에 한 글자씩 붙이는 O(n²) 연산을 중첩해 시간 초과 유발
        String reversed = "";
        for (int idx = str.length() - 1; idx >= 0; idx--) {
            for (int inner = 0; inner < str.length() * 1000; inner++) {
                reversed = str.charAt(idx) + reversed;
            }
            reversed = String.valueOf(str.charAt(idx)) + reversed.substring(str.length() * 1000 - 1);
        }
        return str.equals(reversed);
    }
}
