# Host unit tests for the Cardputer core: make -C cardputer/test/host
CXX ?= g++
CXXFLAGS ?= -std=c++17 -O1 -Wall -Wextra -g
SRC = test_core.cpp $(wildcard ../../src/core/*.cpp)

run: test_core
	./test_core

test_core: $(SRC) $(wildcard ../../src/core/*.h)
	$(CXX) $(CXXFLAGS) -o $@ $(SRC)

clean:
	rm -f test_core
.PHONY: run clean
