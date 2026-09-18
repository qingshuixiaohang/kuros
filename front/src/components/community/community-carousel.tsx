"use client";

import Image from "next/image";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import Link from "next/link";

export type CarouselImage = { src: string; alt: string };

function CarouselImageView({ image, priority = false }: { image: CarouselImage; priority?: boolean }) {
  if (image.src.startsWith("/art/")) {
    return <Image alt={image.alt} fill priority={priority} sizes="(max-width: 720px) 100vw, 820px" src={image.src} />;
  }
  // Uploaded media is a user-controlled backend URL, so it cannot safely be preconfigured in Next's image allowlist.
  // eslint-disable-next-line @next/next/no-img-element
  return <img alt={image.alt} loading={priority ? "eager" : "lazy"} src={image.src} />;
}

function useReducedMotion() {
  const [reducedMotion, setReducedMotion] = useState(false);
  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setReducedMotion(mediaQuery.matches);
    update();
    mediaQuery.addEventListener("change", update);
    return () => mediaQuery.removeEventListener("change", update);
  }, []);
  return reducedMotion;
}

export function CommunityImageCarousel({
  className = "",
  href,
  images,
  label,
  priority = false,
}: {
  className?: string;
  href?: string;
  images: CarouselImage[];
  label: string;
  priority?: boolean;
}) {
  const [activeIndex, setActiveIndex] = useState(0);
  const pointerStart = useRef<number | null>(null);
  const dragged = useRef(false);
  const hasControls = images.length > 1;

  const currentIndex = Math.min(activeIndex, images.length - 1);

  function move(step: number) {
    setActiveIndex((current) => (current + step + images.length) % images.length);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "ArrowLeft") {
      event.preventDefault();
      move(-1);
    } else if (event.key === "ArrowRight") {
      event.preventDefault();
      move(1);
    }
  }

  function handlePointerDown(event: PointerEvent<HTMLDivElement>) {
    if (!hasControls || (event.pointerType === "mouse" && event.button !== 0)) return;
    pointerStart.current = event.clientX;
    dragged.current = false;
  }

  function handlePointerUp(event: PointerEvent<HTMLDivElement>) {
    if (pointerStart.current === null) return;
    const delta = event.clientX - pointerStart.current;
    pointerStart.current = null;
    if (Math.abs(delta) < 40) return;
    dragged.current = true;
    move(delta > 0 ? -1 : 1);
  }

  if (!images.length) return null;

  const image = images[currentIndex];
  const imageContent = href ? (
    <Link
      aria-label={`${label}第 ${currentIndex + 1} 张`}
      className="community-carousel-image-link"
      href={href}
      onClick={(event) => {
        if (dragged.current) {
          event.preventDefault();
          dragged.current = false;
        }
      }}
      rel="noopener noreferrer"
      target="_blank"
    >
      <CarouselImageView image={{ ...image, alt: `${image.alt}第 ${currentIndex + 1} 张` }} priority={priority} />
    </Link>
  ) : <CarouselImageView image={{ ...image, alt: `${image.alt}第 ${currentIndex + 1} 张` }} priority={priority} />;

  return <div
    aria-label={label}
    aria-roledescription="carousel"
    className={`community-carousel ${className}`.trim()}
    onKeyDown={handleKeyDown}
    onPointerDown={handlePointerDown}
    onPointerUp={handlePointerUp}
    role="region"
    tabIndex={0}
  >
    <div className="community-carousel-viewport">
      {imageContent}
      {hasControls && <>
        <button aria-label="上一张" className="community-carousel-arrow community-carousel-arrow--previous" onClick={() => move(-1)} type="button"><ChevronLeft size={18} /></button>
        <button aria-label="下一张" className="community-carousel-arrow community-carousel-arrow--next" onClick={() => move(1)} type="button"><ChevronRight size={18} /></button>
      </>}
    </div>
    {hasControls && <div aria-label={`${label}分页`} className="community-carousel-dots" role="tablist">{images.map((item, index) => <button aria-current={index === currentIndex ? "true" : undefined} aria-label={`${label}第 ${index + 1} 张`} className={index === currentIndex ? "is-active" : ""} key={`${item.src}-${index}`} onClick={() => setActiveIndex(index)} role="tab" type="button" />)}</div>}
  </div>;
}

export type BannerSlide = CarouselImage & { description: string; title: string };

function bannerOffset(index: number, activeIndex: number, total: number) {
  const raw = index - activeIndex;
  if (raw === 0) return 0;
  if (raw === 1 || raw === -(total - 1)) return 1;
  if (raw === -1 || raw === total - 1) return -1;
  return 2;
}

export function CommunityHeroCarousel({ label, slides }: { label: string; slides: BannerSlide[] }) {
  const [activeIndex, setActiveIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const pointerStart = useRef<number | null>(null);
  const reducedMotion = useReducedMotion();

  function move(step: number) {
    setActiveIndex((current) => (current + step + slides.length) % slides.length);
  }

  useEffect(() => {
    if (paused || reducedMotion || slides.length < 2) return;
    const timer = window.setInterval(() => setActiveIndex((current) => (current + 1) % slides.length), 5000);
    return () => window.clearInterval(timer);
  }, [paused, reducedMotion, slides.length]);

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "ArrowLeft") {
      event.preventDefault();
      move(-1);
    } else if (event.key === "ArrowRight") {
      event.preventDefault();
      move(1);
    }
  }

  function handlePointerDown(event: PointerEvent<HTMLElement>) {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    pointerStart.current = event.clientX;
  }

  function handlePointerUp(event: PointerEvent<HTMLElement>) {
    if (pointerStart.current === null) return;
    const delta = event.clientX - pointerStart.current;
    pointerStart.current = null;
    if (Math.abs(delta) >= 40) move(delta > 0 ? -1 : 1);
  }

  return <section
    aria-label={label}
    aria-roledescription="carousel"
    className="community-banner community-banner--carousel"
    id="top"
    onBlurCapture={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setPaused(false); }}
    onFocusCapture={() => setPaused(true)}
    onKeyDown={handleKeyDown}
    onMouseEnter={() => setPaused(true)}
    onMouseLeave={() => setPaused(false)}
    onPointerDown={handlePointerDown}
    onPointerUp={handlePointerUp}
    role="region"
    tabIndex={0}
  >
    <div className="community-banner-stage">{slides.map((slide, index) => {
      const offset = bannerOffset(index, activeIndex, slides.length);
      const position = offset === 0 ? "current" : offset === -1 ? "previous" : offset === 1 ? "next" : "hidden";
      return <article aria-hidden={position !== "current"} className={`community-banner-slide community-banner-slide--${position}`} key={slide.src}>
        <Image alt={position === "current" ? slide.alt : ""} fill priority={position === "current"} sizes="(max-width: 720px) 100vw, 880px" src={slide.src} />
        {position === "current" && <div className="banner-copy"><div className="banner-logo">{slide.title}<small>WUTHERING WAVES</small></div><p>{slide.description}</p></div>}
      </article>;
    })}</div>
    {slides.length > 1 && <>
      <button aria-label="上一张 Banner" className="community-banner-arrow community-banner-arrow--previous" onClick={() => move(-1)} type="button"><ChevronLeft size={19} /></button>
      <button aria-label="下一张 Banner" className="community-banner-arrow community-banner-arrow--next" onClick={() => move(1)} type="button"><ChevronRight size={19} /></button>
      <div aria-label={`${label}分页`} className="community-banner-dots" role="tablist">{slides.map((slide, index) => <button aria-current={index === activeIndex ? "true" : undefined} aria-label={`${label}第 ${index + 1} 张`} className={index === activeIndex ? "is-active" : ""} key={slide.src} onClick={() => setActiveIndex(index)} role="tab" type="button" />)}</div>
    </>}
  </section>;
}
