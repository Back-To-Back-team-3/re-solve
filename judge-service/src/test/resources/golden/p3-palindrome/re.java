class Solution {
    public boolean solution(String str) {
        // null 역참조 → 모든 입력에서 NullPointerException
        String reversed = null;
        return str.equals(reversed.toString());
    }
}
