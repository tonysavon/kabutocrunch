CC       = gcc
CFLAGS   = -O3 -std=c99 -Wall -Wextra -Werror -fomit-frame-pointer
INCLUDES = -Isrc -Isrc/libdivsufsort/include
ifeq ($(OS),Windows_NT)
TARGET   = kcrunch.exe
else
TARGET   = kcrunch
endif
KICKASS  ?= KickAss.jar
ACME     ?= acme

DIVSRC   = src/libdivsufsort/lib/divsufsort.c \
           src/libdivsufsort/lib/divsufsort_utils.c \
           src/libdivsufsort/lib/sssort.c \
           src/libdivsufsort/lib/trsort.c

SRCS     = src/kcrunch.c src/kc_format.c src/kc_search.c src/kc_shrink.c \
           src/kc_matchfinder.c src/decode.c $(DIVSRC)

all: $(TARGET)

$(TARGET): $(SRCS) $(wildcard src/*.h src/libdivsufsort/include/*.h)

	$(CC) $(CFLAGS) $(INCLUDES) $(SRCS) -o $@

cycles: $(TARGET)

	python tools/benchmark_cycles.py --kickass-jar "$(KICKASS)"

test: $(TARGET)

	python tools/test_release.py --kickass-jar "$(KICKASS)"
	python tools/test_examples.py --kickass-jar "$(KICKASS)"

sfx:

	python tools/build_sfx.py --acme "$(ACME)"

clean:

	rm -f $(TARGET)

.PHONY: all cycles clean test sfx
