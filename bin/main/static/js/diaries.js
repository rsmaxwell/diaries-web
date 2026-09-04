document.documentElement.classList.add('js');

(() => {
  const number = (value) => Number.parseFloat(value);
  const clamp = (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value));

  const initialiseMonthReader = () => {
    const reader = document.querySelector('[data-month-reader]');
    if (!reader) return;

    const viewer = reader.querySelector('[data-viewer]');
    const surface = viewer.querySelector('[data-viewer-surface]');
    const svg = viewer.querySelector('[data-viewer-svg]');
    const image = viewer.querySelector('[data-viewer-image]');
    const marquee = viewer.querySelector('[data-viewer-marquee]');
    const pageName = viewer.querySelector('[data-page-name]');
    const svgPageName = viewer.querySelector('[data-svg-page-name]');
    const message = viewer.querySelector('[data-viewer-message]');
    const fragments = Array.from(reader.querySelectorAll('[data-reader-fragment]'));
    const expandButton = viewer.querySelector('[data-viewer-expand]');
    const previousSlot = viewer.querySelector('[data-previous-slot]');
    const nextSlot = viewer.querySelector('[data-next-slot]');
    const pointers = new Map();
    let page = { width: 1, height: 1 };
    let view = { x: 0, y: 0, width: 1, height: 1 };
    let selectedIndex = Math.max(0, fragments.findIndex(item => item.classList.contains('is-selected')));
    let currentPageId = null;
    let previousPointer = null;
    let previousPinch = null;

    const metadata = (fragment) => ({
      id: fragment.dataset.readerFragment,
      pageId: fragment.dataset.pageId,
      pageName: fragment.dataset.pageName,
      imageUrl: fragment.dataset.imageUrl,
      pageWidth: number(fragment.dataset.pageWidth),
      pageHeight: number(fragment.dataset.pageHeight),
      hasMarquee: fragment.dataset.hasMarquee === 'true',
      rectangle: {
        x: number(fragment.dataset.marqueeX),
        y: number(fragment.dataset.marqueeY),
        width: number(fragment.dataset.marqueeWidth),
        height: number(fragment.dataset.marqueeHeight)
      },
      url: fragment.dataset.fragmentUrl
    });
    currentPageId = metadata(fragments[selectedIndex]).pageId;

    const updateViewBox = () => {
      view.width = clamp(view.width, page.width / 12, page.width);
      view.height = clamp(view.height, page.height / 12, page.height);
      svg.setAttribute('viewBox', `${view.x} ${view.y} ${view.width} ${view.height}`);
    };

    const fitPage = () => {
      view = { x: 0, y: 0, width: page.width, height: page.height };
      updateViewBox();
    };

    const fitSelection = (data) => {
      if (!data.hasMarquee) {
        message.textContent = 'The source region is unavailable for this fragment.';
        fitPage();
        return;
      }
      const margin = 0.2;
      const width = Math.max(data.rectangle.width * (1 + margin * 2), page.width / 12);
      const height = Math.max(data.rectangle.height * (1 + margin * 2), page.height / 12);
      view = {
        x: data.rectangle.x - (width - data.rectangle.width) / 2,
        y: data.rectangle.y - (height - data.rectangle.height) / 2,
        width,
        height
      };
      updateViewBox();
    };

    const sourcePoint = (clientX, clientY) => {
      const point = svg.createSVGPoint();
      point.x = clientX;
      point.y = clientY;
      const matrix = svg.getScreenCTM();
      return matrix ? point.matrixTransform(matrix.inverse()) : { x: view.x + view.width / 2, y: view.y + view.height / 2 };
    };

    const zoomAt = (clientX, clientY, factor) => {
      const anchor = sourcePoint(clientX, clientY);
      const width = clamp(view.width * factor, page.width / 12, page.width);
      const height = clamp(view.height * factor, page.height / 12, page.height);
      const xRatio = view.width === 0 ? 0.5 : (anchor.x - view.x) / view.width;
      const yRatio = view.height === 0 ? 0.5 : (anchor.y - view.y) / view.height;
      view = { x: anchor.x - width * xRatio, y: anchor.y - height * yRatio, width, height };
      updateViewBox();
    };

    const keepMarqueeVisible = (data) => {
      if (!data.hasMarquee) return;
      const rectangle = data.rectangle;
      if (rectangle.x < view.x) view.x = rectangle.x;
      if (rectangle.y < view.y) view.y = rectangle.y;
      if (rectangle.x + rectangle.width > view.x + view.width) {
        view.x = rectangle.x + rectangle.width - view.width;
      }
      if (rectangle.y + rectangle.height > view.y + view.height) {
        view.y = rectangle.y + rectangle.height - view.height;
      }
      updateViewBox();
    };

    const setNavigationLink = (slot, index, label, attribute) => {
      slot.replaceChildren();
      if (index < 0 || index >= fragments.length) return;
      const link = document.createElement('a');
      link.href = metadata(fragments[index]).url;
      link.textContent = label;
      link.setAttribute(attribute, '');
      link.addEventListener('click', event => {
        event.preventDefault();
        selectFragment(index, true);
      });
      slot.append(link);
    };

    const selectFragment = (index, addHistory) => {
      if (index < 0 || index >= fragments.length) return;
      const fragment = fragments[index];
      const data = metadata(fragment);
      const pageChanged = currentPageId !== null && currentPageId !== data.pageId;
      selectedIndex = index;
      fragments.forEach((item, itemIndex) => {
        const selected = itemIndex === index;
        item.classList.toggle('is-selected', selected);
        if (selected) item.setAttribute('aria-current', 'true');
        else item.removeAttribute('aria-current');
        const selector = item.querySelector('[data-fragment-selector]');
        if (selector) selector.setAttribute('aria-pressed', String(selected));
      });

      page = { width: data.pageWidth, height: data.pageHeight };
      image.setAttribute('width', data.pageWidth);
      image.setAttribute('height', data.pageHeight);
      marquee.setAttribute('x', data.rectangle.x);
      marquee.setAttribute('y', data.rectangle.y);
      marquee.setAttribute('width', data.rectangle.width);
      marquee.setAttribute('height', data.rectangle.height);
      marquee.classList.toggle('is-unavailable', !data.hasMarquee);
      pageName.textContent = data.pageName;
      svgPageName.textContent = data.pageName;
      message.textContent = data.hasMarquee ? '' : 'The source region is unavailable for this fragment.';

      if (currentPageId !== data.pageId) {
        message.textContent = 'Loading source image…';
        image.setAttribute('href', data.imageUrl);
      }
      currentPageId = data.pageId;
      if (pageChanged || view.width === 1) fitPage();
      else keepMarqueeVisible(data);

      setNavigationLink(previousSlot, index - 1, '← Previous fragment', 'data-previous-fragment');
      setNavigationLink(nextSlot, index + 1, 'Next fragment →', 'data-next-fragment');
      if (addHistory) window.history.pushState({ fragmentId: data.id }, '', data.url);
    };

    image.addEventListener('load', () => { message.textContent = ''; });
    image.addEventListener('error', () => { message.textContent = 'The source image could not be loaded.'; });
    fragments.forEach((fragment, index) => {
      const selector = fragment.querySelector('[data-fragment-selector]');
      selector.addEventListener('click', event => {
        if (event.target.closest('a')) return;
        selectFragment(index, true);
      });
      selector.addEventListener('keydown', event => {
        if (event.target !== selector || (event.key !== 'Enter' && event.key !== ' ')) return;
        event.preventDefault();
        selectFragment(index, true);
      });
    });

    viewer.querySelectorAll('[data-viewer-action]').forEach(button => {
      button.addEventListener('click', () => {
        const bounds = surface.getBoundingClientRect();
        const centreX = bounds.left + bounds.width / 2;
        const centreY = bounds.top + bounds.height / 2;
        const action = button.dataset.viewerAction;
        if (action === 'zoom-in') zoomAt(centreX, centreY, 0.8);
        if (action === 'zoom-out') zoomAt(centreX, centreY, 1.25);
        if (action === 'fit-page') fitPage();
        if (action === 'fit-selection') fitSelection(metadata(fragments[selectedIndex]));
        surface.focus();
      });
    });

    surface.addEventListener('wheel', event => {
      if (document.activeElement !== surface) return;
      event.preventDefault();
      zoomAt(event.clientX, event.clientY, Math.exp(event.deltaY * 0.0015));
    }, { passive: false });

    surface.addEventListener('pointerdown', event => {
      surface.focus();
      surface.setPointerCapture(event.pointerId);
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      previousPointer = { x: event.clientX, y: event.clientY };
      previousPinch = null;
    });
    surface.addEventListener('pointermove', event => {
      if (!pointers.has(event.pointerId)) return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      const values = Array.from(pointers.values());
      if (values.length === 1 && previousPointer) {
        const previousSourcePoint = sourcePoint(previousPointer.x, previousPointer.y);
        const currentSourcePoint = sourcePoint(event.clientX, event.clientY);
        view.x -= currentSourcePoint.x - previousSourcePoint.x;
        view.y -= currentSourcePoint.y - previousSourcePoint.y;
        previousPointer = { x: event.clientX, y: event.clientY };
        updateViewBox();
      } else if (values.length === 2) {
        const distance = Math.hypot(values[1].x - values[0].x, values[1].y - values[0].y);
        const centre = { x: (values[0].x + values[1].x) / 2, y: (values[0].y + values[1].y) / 2 };
        if (previousPinch && distance > 0) zoomAt(centre.x, centre.y, previousPinch.distance / distance);
        previousPinch = { distance, centre };
        previousPointer = null;
      }
    });
    const finishPointer = event => {
      pointers.delete(event.pointerId);
      previousPinch = null;
      const remaining = Array.from(pointers.values())[0];
      previousPointer = remaining || null;
    };
    surface.addEventListener('pointerup', finishPointer);
    surface.addEventListener('pointercancel', finishPointer);

    expandButton.addEventListener('click', () => {
      const expanded = !viewer.classList.contains('is-expanded');
      viewer.classList.toggle('is-expanded', expanded);
      expandButton.setAttribute('aria-expanded', String(expanded));
      expandButton.textContent = expanded ? 'Close expanded image' : 'Expand image';
      document.body.classList.toggle('viewer-is-expanded', expanded);
      window.requestAnimationFrame(fitPage);
    });

    window.addEventListener('keydown', event => {
      if (event.key === 'Escape' && viewer.classList.contains('is-expanded')) expandButton.click();
    });
    window.addEventListener('popstate', () => {
      const fragmentId = new URL(window.location.href).searchParams.get('fragment');
      const index = fragments.findIndex(item => item.dataset.readerFragment === fragmentId);
      selectFragment(index >= 0 ? index : 0, false);
    });
    if ('ResizeObserver' in window) new ResizeObserver(() => updateViewBox()).observe(surface);
    selectFragment(selectedIndex, false);
    fitPage();
    if (!new URL(window.location.href).searchParams.has('fragment')) {
      window.history.replaceState(
        { fragmentId: metadata(fragments[selectedIndex]).id },
        '',
        metadata(fragments[selectedIndex]).url
      );
    }
  };

  const initialiseLegacySourcePage = () => {
    const sourcePage = document.querySelector('[data-source-page]');
    if (!sourcePage) return;
    const marquees = Array.from(sourcePage.querySelectorAll('[data-marquee-fragment]'));
    const transcripts = Array.from(sourcePage.querySelectorAll('[data-transcript-fragment]'));
    const ids = new Set(transcripts.map(item => item.dataset.transcriptFragment));
    if (ids.size === 0) return;
    const select = id => {
      if (!ids.has(id)) return;
      marquees.forEach(item => item.classList.toggle('is-selected', item.dataset.marqueeFragment === id));
      transcripts.forEach(item => item.classList.toggle('is-selected', item.dataset.transcriptFragment === id));
      sourcePage.querySelectorAll('[data-fragment-selector]').forEach(button =>
        button.setAttribute('aria-pressed', String(button.dataset.fragmentSelector === id)));
    };
    sourcePage.querySelectorAll('[data-fragment-selector]').forEach(button =>
      button.addEventListener('click', () => select(button.dataset.fragmentSelector)));
    const hashId = window.location.hash.match(/^#fragment-(\d+)$/)?.[1];
    select(ids.has(hashId) ? hashId : transcripts[0].dataset.transcriptFragment);
  };

  initialiseMonthReader();
  initialiseLegacySourcePage();
})();
