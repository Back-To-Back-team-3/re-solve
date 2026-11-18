import { Button, Modal } from "@/components/ui";

export function EditorResetDialog({ open, onOpenChange, onReset }: {
  open: boolean; onOpenChange: (open: boolean) => void; onReset: () => void;
}) {
  return <Modal open={open} onOpenChange={onOpenChange} title="코드를 초기화할까요?"
    description="현재 언어의 작성 내용을 초기 코드로 바꿉니다.">
    <Button className="danger" onClick={onReset}>초기화</Button>
  </Modal>;
}
