const state = { all: [], market: "ALL", rating: "ALL", query: "" };
const rows = document.querySelector("#stockRows");
const emptyState = document.querySelector("#emptyState");
const dialog = document.querySelector("#stockDialog");

const won = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const usd = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function compactWon(value) {
    if (value >= 1e12) return `${(value / 1e12).toFixed(2)}조원`;
    if (value >= 1e8) return `${(value / 1e8).toFixed(0)}억원`;
    if (value >= 1e4) return `${(value / 1e4).toFixed(0)}만원`;
    return `${won.format(value)}원`;
}

function price(stock) {
    return stock.market === "KR"
        ? `${won.format(stock.price)}원`
        : `$${usd.format(stock.price)}`;
}

function ratingClass(rating) {
    return { RECOMMEND: "recommend", WATCH: "watch", AVOID: "avoid" }[rating] || "watch";
}

function filterItems() {
    const needle = state.query.trim().toLowerCase();
    return state.all.filter(stock =>
        (state.market === "ALL" || stock.market === state.market) &&
        (state.rating === "ALL" || stock.rating === state.rating) &&
        (!needle || stock.name.toLowerCase().includes(needle) || stock.symbol.toLowerCase().includes(needle))
    ).sort((a, b) => b.turnoverKrw - a.turnoverKrw || b.volume - a.volume ||
        b.changePercent - a.changePercent || a.symbol.localeCompare(b.symbol));
}

function render() {
    const items = filterItems();
    rows.innerHTML = items.map((stock, index) => `
        <tr data-market="${stock.market}" data-symbol="${stock.symbol}" tabindex="0" aria-label="${stock.name} 상세 보기">
            <td><div class="stock-cell">
                <span class="rank">${String(index + 1).padStart(2, "0")}</span>
                <span class="ticker-badge">${stock.market}</span>
                <span class="stock-name"><strong>${stock.name}</strong><small>${stock.symbol}</small></span>
            </div></td>
            <td class="price"><strong>${price(stock)}</strong><small>${stock.market === "KR" ? "KRW" : "USD"}</small></td>
            <td class="${stock.changePercent >= 0 ? "positive" : "negative"}">${stock.changePercent >= 0 ? "+" : ""}${stock.changePercent.toFixed(2)}%</td>
            <td class="turnover"><strong>${compactWon(stock.turnoverKrw)}</strong><small>${stock.market === "US" ? `원화 환산 · $${(stock.turnover / 1e9).toFixed(2)}B` : "당일 누적"}</small></td>
            <td><span class="rating rating-${ratingClass(stock.rating)}">${stock.ratingLabel || label(stock.rating)}</span></td>
            <td><span class="score">${stock.score}<small> /100</small></span></td>
        </tr>`).join("");
    emptyState.hidden = items.length > 0;
    document.querySelector(".table-wrap").hidden = items.length === 0;

    const leader = state.all[0];
    if (leader) {
        document.querySelector("#leaderName").textContent = leader.name;
        document.querySelector("#leaderTurnover").textContent = compactWon(leader.turnoverKrw);
        document.querySelector("#updatedAt").textContent = new Date(leader.asOf).toLocaleTimeString("ko-KR");
    }
}

function label(rating) {
    return { RECOMMEND: "추천", WATCH: "관망", AVOID: "비추천", UNRATED: "판정 보류" }[rating];
}

function openDetail(stock) {
    const flow = stock.signals.flowLabel;
    document.querySelector("#dialogContent").innerHTML = `
        <div class="detail">
            <div class="detail-header"><small>${stock.market} · ${stock.symbol} · 가격: ${stock.quoteSource}</small>
                <h2>${stock.name}</h2>
                <span class="detail-price">${price(stock)}</span>
                <span class="${stock.changePercent >= 0 ? "positive" : "negative"}"> ${stock.changePercent >= 0 ? "+" : ""}${stock.changePercent.toFixed(2)}%</span>
            </div>
            <div class="detail-rating">
                <strong>${stock.score}</strong>
                <div><span>${label(stock.rating)}</span><small>신뢰도 ${stock.confidence}% · 1~3개월 관점</small></div>
            </div>
            <div class="signal-bars">
                ${signal("애널리스트", stock.signals.analyst)}
                ${signal(flow, stock.signals.flow)}
                ${signal("가격 모멘텀", stock.signals.momentum)}
            </div>
            <div class="reasons"><h3>이 점수가 나온 이유</h3><ul>${stock.reasons.map(reason => `<li>${reason}</li>`).join("")}</ul>
            <p><small>가격·거래량만 ${stock.quoteSource}이며 추천 분석 신호는 현재 모의 데이터입니다.</small></p></div>
        </div>`;
    dialog.showModal();
}

function signal(name, value) {
    return `<div class="signal-row"><span>${name}</span><span class="bar"><i style="width:${value}%"></i></span><b>${value}</b></div>`;
}

async function load() {
    const [response, statusResponse] = await Promise.all([
        fetch("/api/v1/recommendations"),
        fetch("/api/v1/data-status")
    ]);
    if (!response.ok || !statusResponse.ok) throw new Error("데이터를 불러오지 못했습니다.");
    const data = await response.json();
    const status = await statusResponse.json();
    const live = status.mode === "KIS_LIVE_QUOTES";
    document.querySelector("#dataMode").textContent = live ? "KIS PRICE" : "SIMULATION";
    document.querySelector("#dataMessage").textContent = status.message +
        (live ? " 추천 점수의 애널리스트·수급 신호는 아직 모의 데이터입니다." : " 투자 판단에 사용하지 마세요.");
    document.querySelector("#dataNotice").classList.toggle("live-data", live);
    state.all = data.items;
    render();
}

document.querySelectorAll("[data-market]").forEach(button => button.addEventListener("click", () => {
    document.querySelectorAll("[data-market]").forEach(item => item.classList.remove("active"));
    button.classList.add("active");
    state.market = button.dataset.market;
    render();
}));
document.querySelectorAll("[data-rating]").forEach(button => button.addEventListener("click", () => {
    document.querySelectorAll("[data-rating]").forEach(item => item.classList.remove("active"));
    button.classList.add("active");
    state.rating = button.dataset.rating;
    render();
}));
document.querySelector("#searchInput").addEventListener("input", event => {
    state.query = event.target.value;
    render();
});
rows.addEventListener("click", event => {
    const row = event.target.closest("tr[data-symbol]");
    if (!row) return;
    openDetail(state.all.find(stock => stock.market === row.dataset.market && stock.symbol === row.dataset.symbol));
});
rows.addEventListener("keydown", event => {
    if (event.key !== "Enter" && event.key !== " ") return;
    const row = event.target.closest("tr[data-symbol]");
    if (row) row.click();
});
document.querySelector(".dialog-close").addEventListener("click", () => dialog.close());
dialog.addEventListener("click", event => {
    if (event.target === dialog) dialog.close();
});

load().then(() => {
    const events = new EventSource("/api/v1/stream/quotes");
    events.addEventListener("recommendations", event => {
        state.all = JSON.parse(event.data);
        render();
    });
    events.onerror = () => {
        document.querySelector("#marketStatus").textContent = "재연결 중";
    };
    events.onopen = () => {
        document.querySelector("#marketStatus").textContent = "모의 장중";
    };
}).catch(error => {
    rows.innerHTML = `<tr><td colspan="6" class="loading">${error.message}</td></tr>`;
});
