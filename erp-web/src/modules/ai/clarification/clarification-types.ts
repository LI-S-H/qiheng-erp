export interface ClarificationQuestion {
  id: string;
  question: string;
  options: string[];
  type?: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TEXT';
}

export interface ClarificationAnswer {
  selected: string;
  custom: string;
  selectedOptions?: string[];
}

export interface ClarificationDraft {
  answers: Record<string, ClarificationAnswer>;
  note: string;
  /** undefined 按首个未答题定位，null 表示全部答案摘要。 */
  currentQuestionId?: string | null;
}
