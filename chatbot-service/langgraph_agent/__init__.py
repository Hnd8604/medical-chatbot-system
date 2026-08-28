"""LangGraph agent cho chatbot y tế (M-LG).

Điều phối: router → planner → validator → executor → answer, có semantic cache,
plan cache và model routing theo stage. Xem ``docs/M-langgraph-agent.md``.

Import ``graph`` là lazy để module ``langgraph`` chỉ được nạp khi thật sự bật agent —
các test và luồng ``/chat`` hiện tại không phụ thuộc vào nó.
"""

__all__ = ["run_agent", "get_agent_graph"]


def __getattr__(name: str):
    if name in __all__:
        from langgraph_agent import graph

        return getattr(graph, name)
    raise AttributeError(name)
