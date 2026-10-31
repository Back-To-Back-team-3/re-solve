class Solution {
    public int solution(int[] nums) {
        // nums.length 인덱스 → ArrayIndexOutOfBoundsException
        return nums[nums.length];
    }
}
