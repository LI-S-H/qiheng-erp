export interface ClarificationQuestion {
  id: string;
  question: string;
  options: string[];
}

export interface PlanStep {
  step: number;
  type: 'SQL_QUERY' | 'PYTHON_ANALYSIS';
  instruction: string;
}

export interface Plan {
  title: string;
  objective: string;
  needs_clarification: boolean;
  clarification_questions: ClarificationQuestion[];
  steps: PlanStep[];
}

export interface ClarificationAnswer {
  selected: string;
  custom: string;
}

export interface ClarificationDraft {
  answers: Record<string, ClarificationAnswer>;
  note: string;
  /** undefined 按首个未答题定位，null 表示全部答案摘要。 */
  currentQuestionId?: string | null;
}

export interface EvidenceItem {
  id: string;
  label: string;
  instruction: string;
  source: 'SQL' | 'PYTHON' | 'PROPOSAL';
  depends: string[];
}

export interface Need {
  id: string;
  intent: 'DATA' | 'ADVICE';
  need: string;
  depends: string[];
  condition: { left: string; op: 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NEQ'; right: string | number | boolean } | null;
  evidence_plan: EvidenceItem[];
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'SKIPPED' | 'BLOCKED' | 'INFEASIBLE';
}

/** 同一面板展示真实需求与旧计划，不把需求清单转成虚构执行步骤。 */
export type PlanningArtifact =
  | { type: 'PLAN'; data: { text: string; plan: Plan; threadId?: string } }
  | { type: 'NEEDS'; data: { text: string; needs: Need[]; threadId?: string } };
