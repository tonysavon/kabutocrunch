CC       = gcc
CFLAGS   = -O3 -std=c99 -Wall -Wextra -Werror -fomit-frame-pointer
INCLUDES = -Isrc -Isrc/libdivsufsort/include
ifeq ($(OS),Windows_NT)
TARGET   = kcrunch.exe
LEGAL_TARGET = kcrunch-legal.exe
else
TARGET   = kcrunch
LEGAL_TARGET = kcrunch-legal
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

# Optional alternate executable; normal make and release packaging use TARGET.
legal: $(LEGAL_TARGET)

$(LEGAL_TARGET): $(SRCS) $(wildcard src/*.h src/libdivsufsort/include/*.h)
	$(CC) $(CFLAGS) -DKCRUNCH_LEGAL_OPCODES $(INCLUDES) $(SRCS) -o $@

test-legal: $(TARGET) $(LEGAL_TARGET)
	python tools/test_release.py --legal-opcodes --kickass-jar "$(KICKASS)"
	python tools/test_examples.py --legal-opcodes --kickass-jar "$(KICKASS)"
	python tools/test_plugin.py --legal-opcodes --kickass-jar "$(KICKASS)"

sfx-legal:
	python tools/build_sfx.py --legal-opcodes --acme "$(ACME)"

cycles: $(TARGET)

	python tools/benchmark_cycles.py --kickass-jar "$(KICKASS)"

test: $(TARGET)

	python tools/test_release.py --kickass-jar "$(KICKASS)"
	python tools/test_examples.py --kickass-jar "$(KICKASS)"
	python tools/test_plugin.py --kickass-jar "$(KICKASS)"

sfx:

	python tools/build_sfx.py --acme "$(ACME)"

plugin:

	python tools/build_plugin.py --kickass-jar "$(KICKASS)"

test-plugin: $(TARGET)

	python tools/test_plugin.py --kickass-jar "$(KICKASS)"

clean:

	rm -f $(TARGET) $(LEGAL_TARGET)

.PHONY: all legal test-legal sfx-legal cycles clean test sfx plugin test-plugin
