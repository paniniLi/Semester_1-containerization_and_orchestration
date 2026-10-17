
from pathlib import Path
import matplotlib.pyplot as plt
import numpy as np

labels = ["Без retries", "С retries"]

backend_errors = [16 / 50 * 100, 27 / 75 * 100]
client_errors = [16 / 50 * 100, 2 / 50 * 100]

x = np.arange(len(labels))
width = 0.3

fig, ax = plt.subplots(figsize=(9, 5))

bars1 = ax.bar(
    x - width / 2,
    backend_errors,
    width,
    label="Ошибки backend"
)

bars2 = ax.bar(
    x + width / 2,
    client_errors,
    width,
    label="Ошибки клиента"
)

for bars in [bars1, bars2]:
    for bar in bars:
        height = bar.get_height()
        ax.text(
            bar.get_x() + bar.get_width() / 2,
            height + 1,
            f"{height:.0f}%",
            ha="center",
            fontsize=12
        )

ax.set_title("Влияние Linkerd retries на ошибки HTTP 500")
ax.set_ylabel("Доля ошибок, %")
ax.set_xticks(x)
ax.set_xticklabels(labels)
ax.set_ylim(0, 45)
ax.legend()
ax.grid(axis="y", alpha=0.2)

output = Path(__file__).resolve().parent / "images" / "part4-retries-comparison.png"
output.parent.mkdir(parents=True, exist_ok=True)

plt.tight_layout()
plt.savefig(output, dpi=200)
plt.show()

print(f"График сохранён: {output}")
