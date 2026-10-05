package com.agent.ultra

import com.agent.ultra.agent.ActionGate
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-action gate decides whether a tap can spend money, send a message,
 * or delete something. A false positive costs one tap of the operator's time;
 * a false negative costs whatever the button does. These cases are the line.
 */
class ActionGateTest {

    private fun commits(label: String) =
        assertNotNull("expected \"$label\" to require confirmation", ActionGate.commitmentIn(label))

    private fun passes(label: String) =
        assertNull("expected \"$label\" to run without asking", ActionGate.commitmentIn(label))

    @Test fun moneyButtonsStop() {
        commits("Buy now")
        commits("Buy Now")
        commits("Place your order")
        commits("Proceed to checkout")
        commits("Pay $34.99")
        commits("Confirm payment")
        commits("Confirm and pay")
        commits("Complete purchase")
        commits("Transfer funds")
        commits("Withdraw")
        commits("Subscribe")
        commits("Renew subscription")
        commits("Donate")
        commits("Place bid")
    }

    @Test fun outboundAndDestructiveStop() {
        commits("Send")
        commits("Send message")
        commits("Post")
        commits("Publish")
        commits("Delete")
        commits("Delete account")
        commits("Remove item")
        commits("Submit")
        commits("Book now")
        commits("Reserve")
        commits("Install")
    }

    @Test fun navigationThatSharesAWordDoesNot() {
        passes("Your Orders")
        passes("My Orders")
        passes("Order history")
        passes("Track order")
        passes("Returns & Orders")
        passes("Sent")
        passes("Subscriptions")
        passes("Buy again")
        passes("Payment methods")
        passes("Purchases")
        passes("Deleted items")
        passes("Trash")
    }

    @Test fun ordinaryControlsDoNot() {
        passes("Search")
        passes("Home")
        passes("Back")
        passes("Settings")
        passes("Add to cart")
        passes("Next")
        passes("Skip")
        passes("Cancel")
        passes("Close")
        passes("Menu")
        passes("Sign in")
        passes("Sign up")
        passes("Apply filters")
        passes("Accept all cookies")
        passes("")
        passes("   ")
    }

    @Test fun punctuationAndCaseDoNotHideIt() {
        commits("PAY NOW")
        commits("  Confirm  ")
        commits("Send »")
        commits("Place Your Order →")
    }

    @Test fun longProseIsNotAButton() {
        // A paragraph that merely mentions the word is not a control.
        passes("We will send you an email when your order has shipped to the address on file")
    }
}
