// Compare ipsw's generated address-to-symbol maps independent of gob map order.
package main

import (
	"encoding/gob"
	"fmt"
	"os"
	"reflect"
)

func read(path string) map[uint64]string {
	f, err := os.Open(path)
	if err != nil { panic(err) }
	defer f.Close()
	var symbols map[uint64]string
	if err = gob.NewDecoder(f).Decode(&symbols); err != nil { panic(err) }
	return symbols
}

func main() {
	if len(os.Args) != 3 { panic("Pass two .a2s files") }
	a, b := read(os.Args[1]), read(os.Args[2])
	equal := reflect.DeepEqual(a, b)
	fmt.Printf("left_entries=%d right_entries=%d exact_address_name_map_equal=%t\n", len(a), len(b), equal)
	if !equal { os.Exit(1) }
}
