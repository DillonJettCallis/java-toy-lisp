



(def sum (fn x y (+ x y)))

(print (sum 9 3))

(defn mul x y (* x y))

(print "Defn mul: " (mul 8 3))


(print (import "../testLib.lisp" (div 32 4)))

(def num 100)

(print num)





(print "Equals: (" (= 2 2.0 2.00 (- 4 2) (+ 1 1))  ")")

(print "String add: " (+ "Test: " (* 2 3)))

(print "Nums: " 1 2 3 4 5)

(print "Head and tail: " (head (tail (2 3 4))))

(print "Sum list: " (+ 2 3 7 4))

(print "All basic math ops: " (/ (* (+ 6.5 1.5) (- 4 2) 2) 2))


(print (if (= 2 (- 6 4)) (print "Yay!") (print "Fail!")))


(print ( (fn x y (/ x y)) 12 3))


(print (let x 5 (+ x x)))


(print (let add (fn x y (+ x y)) (add 4 5)))

(print (let m (macro x (eval x)) (m (* 2 3))))

(print (apply + 2 3))



