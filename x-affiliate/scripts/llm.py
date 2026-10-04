"""Claude API 呼び出しの共通処理（構造化出力・拒否時フォールバック・web検索の pause_turn 対応）。

環境変数:
  ANTHROPIC_API_KEY  … 必須（GitHub Secrets）
  CLAUDE_MODEL       … 任意。既定 claude-opus-5-5
  CLAUDE_EFFORT      … 任意。既定 medium（low/medium/high）
"""
from __future__ import annotations

import json
import os

from common import PROMPTS

DEFAULT_MODEL = "claude-opus-5-5"


def render(template: str, **kw) -> str:
    """{name} だけを置換する（{{stat:...}} などの波括弧はそのまま残す）。"""
    for k, v in kw.items():
        template = template.replace("{" + k + "}", str(v))
    return template


def prompt(name: str) -> str:
    return (PROMPTS / name).read_text(encoding="utf-8")


def call_json(user: str, schema: dict, *, system: str | None = None, web_search: bool = False,
              max_tokens: int = 32000) -> dict:
    import anthropic

    client = anthropic.Anthropic()
    model = os.environ.get("CLAUDE_MODEL", DEFAULT_MODEL)
    tools = [{"type": "web_search_20260209", "name": "web_search", "max_uses": 6}] if web_search else []
    messages = [{"role": "user", "content": user}]
    system = system or prompt("system_brand.md")

    for _ in range(4):  # web検索の pause_turn を数回まで継続
        kwargs = dict(
            model=model,
            max_tokens=max_tokens,
            system=system,
            messages=messages,
            output_config={"effort": os.environ.get("CLAUDE_EFFORT", "medium"),
                           "format": {"type": "json_schema", "schema": schema}},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",  # 安全分類器による拒否時は推奨モデルで自動再実行
        )
        if tools:
            kwargs["tools"] = tools
        with client.beta.messages.stream(**kwargs) as stream:
            resp = stream.get_final_message()
        if resp.stop_reason == "refusal":
            raise RuntimeError(f"Claude が生成を拒否しました: {getattr(resp, 'stop_details', None)}")
        if resp.stop_reason == "pause_turn":
            messages = messages + [{"role": "assistant", "content": resp.content}]
            continue
        if resp.stop_reason == "max_tokens":
            raise RuntimeError("max_tokens に到達し出力が途中で切れました")
        texts = [b.text for b in resp.content if b.type == "text"]
        try:
            return json.loads("".join(texts))
        except json.JSONDecodeError:
            return json.loads(texts[-1])
    raise RuntimeError("pause_turn が続いたため中断しました")
