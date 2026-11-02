import { AlertTriangle, Sparkles } from "lucide-react";
import { useTranslation } from "react-i18next";

import { cn } from "~/lib/utils";
import type { UIMessagePart } from "~/types";

/**
 * 读取委托结果消息的 metadata。
 * @see app/src/main/java/me/rerere/rikkahub/data/model/DelegationMessages.kt
 */
export const DELEGATED_FROM_KEY = "delegatedFrom";
export const DELEGATION_CONVERSATION_ID_KEY = "delegationConversationId";
export const DELEGATION_FAILURE_KEY = "delegationFailure";
export const DELEGATION_REMINDER_KEY = "delegationReminder";

function readString(
  metadata: Record<string, unknown> | null | undefined,
  key: string,
): string | null {
  const value = metadata?.[key];
  return typeof value === "string" && value.length > 0 ? value : null;
}

export interface DelegationInfo {
  assistantName: string;
  failed: boolean;
}

/** 委托结果消息的来源信息；不是委托结果时返回 null。 */
export function readDelegationInfo(parts: UIMessagePart[]): DelegationInfo | null {
  for (const part of parts) {
    if (part.type !== "text") continue;
    const metadata = part.metadata;
    if (!metadata || metadata[DELEGATION_REMINDER_KEY] !== true) continue;
    const assistantName = readString(metadata, DELEGATED_FROM_KEY);
    if (!assistantName) continue;
    return {
      assistantName,
      failed: metadata[DELEGATION_FAILURE_KEY] != null,
    };
  }
  return null;
}

/**
 * 委托结果抬头。来源助手名来自消息 metadata，只用于展示，不会进入发给模型的正文。
 */
export function ChatMessageDelegationHeader({
  info,
  className,
}: {
  info: DelegationInfo;
  className?: string;
}) {
  const { t } = useTranslation("message");
  const Icon = info.failed ? AlertTriangle : Sparkles;

  return (
    <div
      className={cn(
        "inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium",
        info.failed
          ? "bg-destructive/10 text-destructive"
          : "bg-secondary text-secondary-foreground",
        className,
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      <span>
        {info.failed
          ? t("chat_message.delegation_failed_from", {
              name: info.assistantName,
              defaultValue: "Delegated task for {{name}} failed",
            })
          : t("chat_message.delegation_result_from", {
              name: info.assistantName,
              defaultValue: "Reply from {{name}}",
            })}
      </span>
    </div>
  );
}
