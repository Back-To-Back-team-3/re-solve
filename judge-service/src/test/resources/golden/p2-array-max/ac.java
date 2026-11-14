class Solution {
    public int solution(int[] nums) {
        int max = nums[0];
        for (int idx = 1; idx < nums.length; idx++) {
            if (nums[idx] > max) {
                max = nums[idx];
            }
        }
        return max;
    }
}
