// Filter bars above the Herd and Gate Readers lists (a <form data-filter-bar>).
//  - choosing a facility keeps only its barns in the barn list
//  - a chosen filter is highlighted, and the bar says how many are on
//  - "Clear filters" switches them all off and asks the server for the list again
// The form itself asks the server (htmx), so every filter that is on applies together.
(function () {
  function paint(form) {
    var on = 0;
    form.querySelectorAll('select').forEach(function (s) {
      var active = s.value !== '';
      if (active) on++;
      ['border-blue-400', 'bg-blue-50', 'text-blue-800', 'font-semibold'].forEach(function (c) { s.classList.toggle(c, active); });
      ['border-gray-300', 'bg-white'].forEach(function (c) { s.classList.toggle(c, !active); });
    });
    var count = form.querySelector('[data-filter-count]');
    if (count) count.textContent = on === 0 ? 'No filters' : on + (on === 1 ? ' filter on' : ' filters on');
    var clear = form.querySelector('[data-clear-filters]');
    if (clear) clear.classList.toggle('hidden', on === 0);
  }

  function wire(form) {
    var farm = form.querySelector('select[name="farm"]');
    var barn = form.querySelector('select[name="barn"][data-all]');
    if (farm && barn) {
      var all = [];
      try { all = JSON.parse(barn.dataset.all || '[]'); } catch (e) { all = []; }
      farm.addEventListener('change', function () {
        var keep = barn.value;
        while (barn.options.length > 1) barn.remove(1);
        all.forEach(function (b) {
          if (farm.value && b[2] !== farm.value) return;
          var o = document.createElement('option');
          o.value = b[0];
          o.textContent = b[1];
          barn.appendChild(o);
        });
        barn.value = Array.prototype.some.call(barn.options, function (o) { return o.value === keep; }) ? keep : '';
      });
    }
    form.addEventListener('change', function () { paint(form); });
    var clear = form.querySelector('[data-clear-filters]');
    if (clear) {
      clear.addEventListener('click', function () {
        var selects = form.querySelectorAll('select');
        selects.forEach(function (s) { s.value = ''; });
        var first = farm || selects[0];
        if (first) first.dispatchEvent(new Event('change', { bubbles: true }));   // rebuilds the barn list and asks for the list
      });
    }
    paint(form);
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('form[data-filter-bar]').forEach(wire);
  });
})();
