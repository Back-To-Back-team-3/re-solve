class Solution {
    public int solution(int[] nums) {
        int max = Integer.MIN_VALUE;
        int idx = 0;
        // idx 증가 없음 → 무한 루프
        while (idx < nums.length) {
            if (nums[idx] > max) {
                max = nums[idx];
            }
        }
        return max;
    }
}
